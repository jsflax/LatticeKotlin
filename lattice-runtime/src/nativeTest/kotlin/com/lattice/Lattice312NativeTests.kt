package com.lattice

import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

@LatticeEnum
enum class NativePriority312 { LOW, MEDIUM, HIGH }

@Embedded
@Serializable
data class NativeVerdict312(
    val decision: String = "proceed",
    val confidence: Double = 1.0
)

@Union
sealed interface NativeSignal312 {
    @UnionCase("none")
    data object None : NativeSignal312

    @UnionCase("direct")
    data class Direct(val target: String = "", val hops: Int = 1) : NativeSignal312

    @UnionCase("verdict")
    data class Verdict(@Unlabeled val payload: NativeVerdict312 = NativeVerdict312()) : NativeSignal312
}

@Model(tableName = "native_v312_items")
class NativeItem312 {
    @Unique(allowsUpsert = true)
    var slug: String = ""
    @NoHistory
    var liveStream: String = ""
    @Property(name = "mapped_note")
    var note: String = ""
    var tags: Set<NativePriority312> = emptySet()
    var verdicts: List<NativeVerdict312> = emptyList()
    var signal: NativeSignal312 = NativeSignal312.None
    var bounds: GeoBounds = GeoBounds.point(0.0, 0.0)
    var vec: FloatVector = FloatVector()
}

class Lattice312NativeTests {
    private val paths = mutableListOf<String>()
    private val dbs = mutableListOf<Lattice>()

    private fun freshPath(tag: String): String {
        val p = "/tmp/lattice_312_native_${tag}_${Random.nextLong().toULong().toString(16)}.sqlite"
        paths += p
        return p
    }

    @BeforeTest
    fun setup() {
        Lattice.registerFactory(NativeItem312::class) { NativeItem312() }
        Lattice.registerHydrationFactory(NativeItem312::class) { h -> NativeItem312(h, LatticeHydrationMarker) }
    }

    @AfterTest
    fun teardown() {
        dbs.reversed().forEach { runCatching { it.close() } }
        dbs.clear()
        paths.forEach { Lattice.deleteDatabase(it) }
        paths.clear()
    }

    @Test
    fun testUnionsNoHistoryCollectionsGeoVectorAndCachedFlowOnNative() = runBlocking {
        val pathA = freshPath("a")
        val pathB = freshPath("b")
        val dbA = Lattice(pathA, NativeItem312::class).also { dbs += it }
        val dbB = Lattice(pathB, NativeItem312::class).also { dbs += it }

        val item = NativeItem312().apply {
            slug = "room-1"
            liveStream = "tok-0"
            note = "custom-col-ok"
            tags = setOf(NativePriority312.LOW, NativePriority312.HIGH)
            verdicts = listOf(NativeVerdict312("approve", 0.95), NativeVerdict312("escalate", 0.4))
            signal = NativeSignal312.Verdict(NativeVerdict312("ship-it", 0.99))
            bounds = GeoBounds.point(37.7749, -122.4194)
            vec = FloatVector(floatArrayOf(1.0f, 0.0f, 0.0f))
        }
        dbA.add(item)

        // Stream 15 @NoHistory updates without bloating AuditLog
        val eventsBefore = dbA.eventsAfter(null)
        assertNotNull(eventsBefore)
        repeat(15) { idx ->
            item.liveStream = "tok-${idx + 1}"
        }
        val eventsAfter = dbA.eventsAfter(null)
        assertNotNull(eventsAfter)
        assertEquals("tok-15", dbA.objects(NativeItem312::class).first()?.liveStream)

        // Replicate AuditLog events to dbB: late-bound @NoHistory + @Union + @Property + collections round-trip
        val acked = dbB.receive("""{"kind":"auditLog","auditLog":$eventsAfter}""".encodeToByteArray())
        assertTrue(acked.isNotEmpty())

        val replicated = dbB.objects(NativeItem312::class).first()
        assertNotNull(replicated)
        assertEquals("room-1", replicated.slug)
        assertEquals("tok-15", replicated.liveStream)
        assertEquals("custom-col-ok", replicated.note)
        assertEquals(setOf(NativePriority312.LOW, NativePriority312.HIGH), replicated.tags)
        assertEquals(2, replicated.verdicts.size)
        assertEquals("approve", replicated.verdicts[0].decision)
        val sig = replicated.signal
        assertTrue(sig is NativeSignal312.Verdict, "Expected Verdict union case, got $sig")
        assertEquals("ship-it", sig.payload.decision)
        assertEquals(0.99, sig.payload.confidence, 1e-6)

        // Switch union case to Direct + None
        replicated.signal = NativeSignal312.Direct("planner", 3)
        val sig2 = dbB.objects(NativeItem312::class).first()?.signal
        assertEquals(NativeSignal312.Direct("planner", 3), sig2)
        replicated.signal = NativeSignal312.None
        assertEquals(NativeSignal312.None, dbB.objects(NativeItem312::class).first()?.signal)

        // GeoBounds + KNN vector query
        val sfHits = dbA.objects(NativeItem312::class)
            .withinBounds("bounds", 37.0, 38.0, -123.0, -122.0)
        assertEquals(1, sfHits.size)

        val knnHits = dbA.objects(NativeItem312::class)
            .nearest(to = FloatVector(floatArrayOf(0.9f, 0.1f, 0.0f)), on = "vec", k = 1, metric = DistanceMetric.COSINE)
        assertEquals(1, knnHits.size)

        // Results.asFlow(cacheRows = true): consumer thread executes 0 SQLite statements
        val snapshot = dbA.objects(NativeItem312::class)
            .asFlow(queryDispatcher = Dispatchers.Default, cacheRows = true)
            .first()
        assertEquals(1, snapshot.size)
        assertTrue(snapshot[0].isRowCacheEnabled())
        val beforeStmts = Lattice.threadStatementCount()
        assertEquals("room-1", snapshot[0].slug)
        assertEquals("custom-col-ok", snapshot[0].note)
        val afterStmts = Lattice.threadStatementCount()
        assertEquals(0L, afterStmts - beforeStmts, "Cached row reads must execute 0 SQLite statements")
    }
}
