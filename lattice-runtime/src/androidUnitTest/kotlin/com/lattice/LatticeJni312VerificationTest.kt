package com.lattice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import java.io.File

@Embedded
@Serializable
data class JniVerdictPayload(
    var pass: Boolean = false,
    var notes: String = "",
    var blocking: Boolean = false,
)

@Serializable
data class JniFanOutItem(
    var target: String = "",
    var task: String = "",
)

@Embedded
@Serializable
data class JniFanOutPayload(
    var assignments: List<JniFanOutItem> = emptyList(),
)

@Embedded
@Serializable
data class JniReasoningSegment(
    var mark: Int = 0,
    var text: String = "",
)

@LatticeEnum
@Serializable
enum class JniCapability {
    createRooms,
    runTools,
    readTranscript,
}

@Union
@Serializable
sealed interface JniRoutingSignal {
    @UnionCase("none")
    @Serializable
    data object None : JniRoutingSignal

    @UnionCase("handoff")
    @Serializable
    data class Handoff(
        val target: String = "",
        val note: String = "",
    ) : JniRoutingSignal

    @UnionCase("done")
    @Serializable
    data class Done(
        val summary: String = "",
    ) : JniRoutingSignal

    @UnionCase("consult")
    @Serializable
    data class Consult(
        val question: String = "",
    ) : JniRoutingSignal

    @UnionCase("verdict")
    @Serializable
    data class Verdict(
        val value: JniVerdictPayload = JniVerdictPayload(),
    ) : JniRoutingSignal

    @UnionCase("override")
    @Serializable
    data class Override(
        val referee: String = "",
        val justification: String = "",
        val group: String = "",
    ) : JniRoutingSignal

    @UnionCase("fanOut")
    @Serializable
    data class FanOut(
        val value: JniFanOutPayload = JniFanOutPayload(),
    ) : JniRoutingSignal
}

@Union
@Serializable
sealed interface JniDistributedState {
    @UnionCase("none")
    @Serializable
    data object None : JniDistributedState

    @UnionCase("waitingForPeer")
    @Serializable
    data class WaitingForPeer(
        val reason: String = "",
    ) : JniDistributedState

    @UnionCase("runningDistributed")
    @Serializable
    data class RunningDistributed(
        val peerMachineId: String = "",
        val boundary: Long = 0L,
        val ringSize: Long = 0L,
    ) : JniDistributedState
}

@OptIn(ExperimentalUuidApi::class)
@Model
class JniAgent {
    @Unique var agentId: String = ""
    var name: String = ""
    var scopes: Set<JniCapability> = emptySet()
    var verdict: JniVerdictPayload? = null
}

@OptIn(ExperimentalUuidApi::class)
@Model
class JniRoom {
    @Unique var code: String = ""
    var distributedState: JniDistributedState = JniDistributedState.None
    @Link var rootAgent: JniAgent? = null
}

@OptIn(ExperimentalUuidApi::class)
@Model
class JniAction {
    @Link var agent: JniAgent? = null
    var tool: String = ""
    var signal: JniRoutingSignal = JniRoutingSignal.None
}

@OptIn(ExperimentalUuidApi::class)
@Model
class JniChatMessage {
    @NoHistory var text: String = ""
    @NoHistory var reasoningSegments: List<JniReasoningSegment> = emptyList()
    var createdAt: Instant = Instant.fromEpochSeconds(1_700_000_000L)
    var groupId: Uuid? = null
    @Link var author: JniAgent? = null
    var embedding: FloatVector = FloatVector()
}

@Model
class JniPlace {
    var name: String = ""
    var bounds: GeoBounds = GeoBounds.point(0.0, 0.0)
    var visits: Long = 0L
}

@OptIn(ExperimentalUuidApi::class)
class LatticeJni312VerificationTest {

    private val createdPaths = mutableListOf<String>()
    private val openDbs = mutableListOf<Lattice>()

    private fun openTempDb(vararg types: kotlin.reflect.KClass<out LatticeObject>, configBlock: (Configuration) -> Configuration = { it }): Lattice {
        val f = File.createTempFile("lattice_jni_312_", ".sqlite")
        val path = f.absolutePath
        f.delete()
        createdPaths += path
        val cfg = configBlock(Configuration(path = path, targetSchemaVersion = 31, auditRetentionSeconds = 600L))
        val db = Lattice(cfg, *types)
        openDbs += db
        return db
    }

    @AfterTest
    fun tearDown() {
        openDbs.forEach { runCatching { it.close() } }
        openDbs.clear()
        createdPaths.forEach { Lattice.deleteDatabase(it) }
        createdPaths.clear()
    }

    @Test
    fun `unions round-trip all 7 RoutingSignal and 3 DistributedState cases including unlabeled Embedded and sync`() {
        val db1 = openTempDb(JniAgent::class, JniRoom::class, JniAction::class)
        val db2 = openTempDb(JniAgent::class, JniRoom::class, JniAction::class)

        val agent = db1.add(JniAgent().apply {
            agentId = "orb-1"
            name = "Orb"
            scopes = setOf(JniCapability.runTools, JniCapability.createRooms)
            verdict = JniVerdictPayload(pass = true, notes = "clean", blocking = false)
        })

        val room = db1.add(JniRoom().apply {
            code = "RM-100"
            rootAgent = agent
            distributedState = JniDistributedState.RunningDistributed(
                peerMachineId = "mac-studio-1",
                boundary = 16L,
                ringSize = 2L
            )
        })

        val allSignals = listOf(
            JniRoutingSignal.None,
            JniRoutingSignal.Handoff(target = "verifier", note = "please check"),
            JniRoutingSignal.Done(summary = "shipped"),
            JniRoutingSignal.Consult(question = "use Vulkan or OpenGL?"),
            JniRoutingSignal.Verdict(JniVerdictPayload(pass = true, notes = "100% green", blocking = true)),
            JniRoutingSignal.Override(referee = "lead", justification = "tested", group = "g1"),
            JniRoutingSignal.FanOut(JniFanOutPayload(listOf(JniFanOutItem("a1", "slice-1"), JniFanOutItem("a2", "slice-2"))))
        )

        allSignals.forEachIndexed { idx, sig ->
            db1.add(JniAction().apply {
                this.agent = agent
                tool = "tool-$idx"
                signal = sig
            })
        }

        val fetchedActions = db1.objects(JniAction::class).orderBy("id").toList()
        assertEquals(allSignals, fetchedActions.map { it.signal })

        val fetchedRoom = db1.objects(JniRoom::class).first()!!
        assertEquals(
            JniDistributedState.RunningDistributed("mac-studio-1", 16L, 2L),
            fetchedRoom.distributedState
        )
        assertEquals(setOf(JniCapability.createRooms, JniCapability.runTools), fetchedRoom.rootAgent?.scopes)

        // Mutate union case in-place
        room.distributedState = JniDistributedState.WaitingForPeer(reason = "awaiting LAN host")
        assertEquals(
            JniDistributedState.WaitingForPeer("awaiting LAN host"),
            db1.objects(JniRoom::class).first()!!.distributedState
        )

        // Verify sync replication across databases (including _JniRoutingSignal & _JniDistributedState rows)
        val eventsJson = db1.eventsAfter(null)
        assertNotNull(eventsJson)
        val ssePayload = """{"kind":"auditLog","auditLog":$eventsJson}""".encodeToByteArray()
        val acked = db2.receive(ssePayload)
        assertTrue(acked.isNotEmpty())

        val syncedActions = db2.objects(JniAction::class).orderBy("id").toList()
        assertEquals(allSignals, syncedActions.map { it.signal })
        assertEquals(
            JniDistributedState.WaitingForPeer("awaiting LAN host"),
            db2.objects(JniRoom::class).first()!!.distributedState
        )
    }

    @Test
    fun `NoHistory streaming updates late-bind on sync upload and asFlow row cache issues zero SQLite statements on UI thread`() = runBlocking {
        val db1 = openTempDb(JniAgent::class, JniChatMessage::class)
        val db2 = openTempDb(JniAgent::class, JniChatMessage::class)

        val author = db1.add(JniAgent().apply {
            agentId = "seat-1"
            name = "Architect"
            scopes = setOf(JniCapability.readTranscript)
        })

        val msg = db1.add(JniChatMessage().apply {
            text = "tok-0"
            reasoningSegments = listOf(JniReasoningSegment(0, "thinking-0"))
            this.author = author
            groupId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
            embedding = FloatVector(floatArrayOf(1f, 0f, 0f, 0f))
        })

        // Simulate 25 rapid streaming token deltas on @NoHistory columns
        for (i in 1..25) {
            msg.text = "tok-$i"
            msg.reasoningSegments = listOf(JniReasoningSegment(i, "thinking-$i"))
        }

        // Sync upload late-binds final live value into db2
        val events = db1.eventsAfter(null)
        db2.receive("""{"kind":"auditLog","auditLog":$events}""".encodeToByteArray())
        val synced = db2.objects(JniChatMessage::class).first()!!
        assertEquals("tok-25", synced.text)
        assertEquals(listOf(JniReasoningSegment(25, "thinking-25")), synced.reasoningSegments)

        // Verify asFlow(cacheRows = true) materializes rows off-thread so subsequent reads issue 0 SQLite statements!
        val snapshot = db1.objects(JniChatMessage::class)
            .asFlow(queryDispatcher = Dispatchers.IO, cacheRows = true)
            .first()
        assertEquals(1, snapshot.size)
        val cachedMsg = snapshot[0]
        assertTrue(cachedMsg.isRowCacheEnabled())

        val beforeStmts = Lattice.threadStatementCount()
        assertEquals("tok-25", cachedMsg.text)
        assertEquals(listOf(JniReasoningSegment(25, "thinking-25")), cachedMsg.reasoningSegments)
        assertEquals(Uuid.parse("550e8400-e29b-41d4-a716-446655440000"), cachedMsg.groupId)
        assertEquals(Instant.fromEpochSeconds(1_700_000_000L), cachedMsg.createdAt)
        val afterStmts = Lattice.threadStatementCount()

        assertEquals(0L, afterStmts - beforeStmts, "Expected ZERO SQLite statements when reading cached row fields on UI thread")
    }

    @Test
    fun `KNN vector search GeoBounds RTree atomic increment detachedJson and TLS pin validation work over JNI`() {
        val db = openTempDb(JniAgent::class, JniChatMessage::class, JniPlace::class)

        db.add(JniChatMessage().apply {
            text = "near"
            embedding = FloatVector(floatArrayOf(1f, 0f, 0f, 0f))
        })
        db.add(JniChatMessage().apply {
            text = "far"
            embedding = FloatVector(floatArrayOf(0f, 10f, 0f, 0f))
        })

        val knn = db.objects(JniChatMessage::class).nearest(
            to = FloatVector(floatArrayOf(0f, 0f, 0f, 0f)),
            on = "embedding",
            k = 2,
            metric = DistanceMetric.L2
        )
        assertEquals(listOf("near", "far"), knn.map { it.`object`.text })
        assertEquals(1.0, knn[0].distance, 1e-6)

        val sf = db.add(JniPlace().apply {
            name = "SF"
            bounds = GeoBounds.point(37.7749, -122.4194)
            visits = 10L
        })
        db.add(JniPlace().apply {
            name = "NYC"
            bounds = GeoBounds.point(40.7128, -74.0060)
            visits = 5L
        })

        val inBayArea = db.objects(JniPlace::class)
            .withinBounds("bounds", 37.0, 38.5, -123.0, -121.5)
        assertEquals(listOf("SF"), inBayArea.map { it.name })

        sf.incrementInt("visits", 7L)
        assertEquals(17L, sf.visits)
        assertTrue(sf.toJson(maxDepth = 1).isNotEmpty())
        assertTrue(db.recordAuditWatermark())
        assertTrue(db.checkpoint(truncate = false))

        // Certificate pin validation check
        val ws = KtorWebSocketFactory().createWebSocket()
        assertFalse(ws.configureCertificatePin("not-valid-hex"))
        assertTrue(ws.configureCertificatePin("a".repeat(64)))
        assertTrue(ws.configureCertificatePin(""))
    }
}
