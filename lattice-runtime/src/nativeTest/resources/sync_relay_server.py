#!/usr/bin/env python3
"""
WebSocket relay server for Lattice sync integration tests.

Matches the Swift Vapor test server pattern:
- Binds to port 0 (OS assigns free port), prints port to stdout
- On client connect: optionally sends catch-up events
- On client message (auditLog): sends ACK back to sender, broadcasts to other clients
- Persists events to an in-memory store for catch-up on reconnect

Protocol:
- Messages are binary JSON: {"kind":"auditLog","auditLog":[...]}
- ACK response: {"kind":"ack","ack":["globalId1","globalId2",...]}
- Catch-up on connect uses ?last-event-id=UUID query parameter
"""

import asyncio
import json
import signal
import sys
from urllib.parse import urlparse, parse_qs
import websockets
import websockets.server


# In-memory event store for catch-up support
event_store = []  # List of (globalId, raw_message_bytes)
event_store_lock = asyncio.Lock()

# Connected clients
clients = set()
clients_lock = asyncio.Lock()


async def handle_client(websocket):
    """Handle a single WebSocket client connection."""

    # Parse last-event-id from query string
    path = websocket.request.path if hasattr(websocket.request, 'path') else ""
    # websockets 12+ uses websocket.request
    try:
        query_string = path.split("?", 1)[1] if "?" in path else ""
        params = parse_qs(query_string)
        last_event_id = params.get("last-event-id", [None])[0]
    except Exception:
        last_event_id = None

    # Register client
    async with clients_lock:
        clients.add(websocket)

    try:
        # Send catch-up events if client has a last-event-id
        async with event_store_lock:
            if last_event_id:
                # Find events after the given ID
                found = False
                for global_id, raw_msg in event_store:
                    if found:
                        await websocket.send(raw_msg)
                    elif global_id == last_event_id:
                        found = True
                # If last-event-id not found, send all events (full catch-up)
                if not found and event_store:
                    for _, raw_msg in event_store:
                        await websocket.send(raw_msg)
            elif event_store:
                # No last-event-id: send all events for initial sync
                for _, raw_msg in event_store:
                    await websocket.send(raw_msg)

        # Process messages
        async for message in websocket:
            # message can be bytes or str
            if isinstance(message, bytes):
                data = message
                text = message.decode("utf-8", errors="replace")
            else:
                data = message.encode("utf-8")
                text = message

            try:
                parsed = json.loads(text)
            except json.JSONDecodeError:
                continue

            kind = parsed.get("kind")
            if kind == "auditLog":
                audit_logs = parsed.get("auditLog", [])
                global_ids = []
                for entry in audit_logs:
                    gid = entry.get("globalId")
                    if gid:
                        global_ids.append(gid)

                # Send ACK back to sender immediately
                if global_ids:
                    ack = json.dumps({"kind": "ack", "ack": global_ids})
                    try:
                        await websocket.send(ack.encode("utf-8"))
                    except Exception:
                        pass

                # Broadcast to other clients
                async with clients_lock:
                    others = [c for c in clients if c != websocket]
                for other in others:
                    try:
                        await other.send(data)
                    except Exception:
                        pass

                # Persist for catch-up
                # Store using the last globalId as the event marker
                if global_ids:
                    async with event_store_lock:
                        for gid in global_ids:
                            event_store.append((gid, data))

    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        async with clients_lock:
            clients.discard(websocket)


async def main():
    # Bind to port 0 to get a free port
    stop = asyncio.get_event_loop().create_future()

    async with websockets.serve(handle_client, "127.0.0.1", 0) as server:
        # Get the assigned port
        port = server.sockets[0].getsockname()[1]

        # Print port to stdout so the parent process can read it
        # Use a specific format for reliable parsing
        print(f"PORT:{port}", flush=True)

        # Handle shutdown signals
        def shutdown():
            if not stop.done():
                stop.set_result(None)

        loop = asyncio.get_event_loop()
        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(sig, shutdown)

        await stop

    sys.exit(0)


if __name__ == "__main__":
    asyncio.run(main())
