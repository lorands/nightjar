package dev.nightjar.migrations.data

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class DataMigrationEngineTest {

    private val store = InMemoryDataMigrationStore()
    private var engine: DataMigrationEngine? = null

    private fun engine(configure: DataMigrationEngine.Builder.() -> Unit = {}): DataMigrationEngine =
        DataMigrationEngine.builder()
            .store(store)
            .batchSize(2)
            .sequentialBatchSize(2)
            .apply(configure)
            .build()
            .also { engine = it }

    @AfterTest
    fun tearDown() {
        engine?.close()
    }

    // ------------------------------------------------------------------ parallel

    @Test
    fun `processes parallel batches respecting batch size`() {
        val batches = mutableListOf<List<String>>()
        val e = engine()
        e.register("reindex") { _, ids -> batches.add(ids) }

        e.enqueue("reindex", listOf("1", "2", "3", "4", "5"))
        e.pollOnce()

        assertEquals(listOf(2, 2, 1), batches.map { it.size })
        assertEquals(listOf("1", "2", "3", "4", "5"), batches.flatten().sorted())
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `lower priority value processes first`() {
        val order = mutableListOf<String>()
        val e = engine()
        e.register("low-prio") { type, _ -> order.add(type) }
        e.register("high-prio") { type, _ -> order.add(type) }

        e.enqueue("low-prio", listOf("a"), 200)
        e.enqueue("high-prio", listOf("b"), 1)
        e.pollOnce()

        assertEquals(listOf("high-prio", "low-prio"), order)
    }

    @Test
    fun `failed batch is released with attempts counted and retried`() {
        var failing = true
        val processed = mutableListOf<String>()
        val e = engine()
        e.register("flaky") { _, ids ->
            if (failing) throw IllegalStateException("boom")
            processed.addAll(ids)
        }

        e.enqueue("flaky", listOf("x"))
        e.pollOnce()
        assertEquals(1, store.pendingCount(), "failed rows must remain queued")
        assertEquals(1, store.attemptsOf("x"))

        failing = false
        e.pollOnce()
        assertEquals(listOf("x"), processed)
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `failing type does not block healthy types within the same poll`() {
        val processed = mutableListOf<String>()
        val e = engine()
        e.register("broken") { _, _ -> throw IllegalStateException("boom") }
        e.register("healthy") { type, _ -> processed.add(type) }

        e.enqueue("broken", listOf("b"), 1)      // higher priority than healthy
        e.enqueue("healthy", listOf("h"), 100)
        e.pollOnce()

        assertEquals(listOf("healthy"), processed)
        assertEquals(1, store.pendingCount(), "only the broken row remains")
        assertEquals(1, store.attemptsOf("b"), "broken type fails exactly once per poll")
    }

    @Test
    fun `batch without a handler is released untouched`() {
        val e = engine()
        e.enqueue("nobody-handles-this", listOf("a", "b"))
        e.pollOnce()

        assertEquals(2, store.pendingCount())
        assertEquals(0, store.attemptsOf("a"))
    }

    @Test
    fun `expired claims are reclaimable`() {
        val clock = MutableClock(Instant.parse("2026-06-04T10:00:00Z"))
        val agingStore = InMemoryDataMigrationStore(clock)
        agingStore.enqueue("orphaned", listOf("1"), 100)

        // a crashed instance claimed the batch and never finished
        assertNotNull(agingStore.claimNextBatch("dead-instance", 10, Duration.ofMinutes(10), emptySet()))
        assertNull(agingStore.claimNextBatch("live-instance", 10, Duration.ofMinutes(10), emptySet()))

        clock.advance(Duration.ofMinutes(11))
        val reclaimed = assertNotNull(agingStore.claimNextBatch("live-instance", 10, Duration.ofMinutes(10), emptySet()))
        assertEquals(listOf("1"), reclaimed.ids)
    }

    // ---------------------------------------------------------------- sequential

    @Test
    fun `sequential queue preserves strict global order across types`() {
        val calls = Collections.synchronizedList(mutableListOf<Pair<String, List<String>>>())
        val e = engine()
        e.register("alpha") { type, ids -> calls.add(type to ids) }
        e.register("beta") { type, ids -> calls.add(type to ids) }

        e.enqueueSequential("alpha", listOf("a1", "a2"))
        e.enqueueSequential("beta", listOf("b1"))
        e.enqueueSequential("alpha", listOf("a3"))
        e.pollOnce()

        // a3 must NOT jump ahead of b1 even though a1/a2 are also alpha
        assertEquals(
            listOf(
                "alpha" to listOf("a1", "a2"),
                "beta" to listOf("b1"),
                "alpha" to listOf("a3"),
            ),
            calls,
        )
        assertEquals(0, store.sequentialPendingCount())
    }

    @Test
    fun `failing sequential head blocks the whole queue until it succeeds`() {
        var failing = true
        val calls = mutableListOf<String>()
        val e = engine()
        e.register("first") { _, ids -> if (failing) throw IllegalStateException("head broken") else calls.addAll(ids) }
        e.register("second") { _, ids -> calls.addAll(ids) }

        e.enqueueSequential("first", listOf("f1"))
        e.enqueueSequential("second", listOf("s1"))

        e.pollOnce()
        assertEquals(emptyList(), calls, "nothing may overtake a failing head")
        assertEquals(2, store.sequentialPendingCount())

        failing = false
        e.pollOnce()
        assertEquals(listOf("f1", "s1"), calls)
    }

    @Test
    fun `rejects duplicate handler registration`() {
        val e = engine()
        e.register("once") { _, _ -> }
        kotlin.test.assertFailsWith<IllegalArgumentException> { e.register("once") { _, _ -> } }
    }

    private class MutableClock(private var now: Instant) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun instant(): Instant = now
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }
}
