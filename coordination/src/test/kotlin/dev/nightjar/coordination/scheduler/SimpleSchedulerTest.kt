package dev.nightjar.coordination.scheduler

import dev.nightjar.coordination.MutableClock
import dev.nightjar.coordination.lock.InMemoryProcessLock
import dev.nightjar.coordination.worker.InMemoryWorkerPool
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SimpleSchedulerTest {

    private val clock = MutableClock()
    private val store = InMemorySchedulerStore()
    private val lock = InMemoryProcessLock(clock)
    private var scheduler: SimpleScheduler? = null

    private fun scheduler(configure: SimpleScheduler.Builder.() -> Unit = {}): SimpleScheduler =
        SimpleScheduler.builder()
            .store(store)
            .lock(lock)
            .clock(clock)
            .retryDelay(Duration.ofMinutes(5))
            .apply(configure)
            .build()
            .also { scheduler = it }

    @AfterTest
    fun tearDown() {
        scheduler?.close()
    }

    @Test
    fun `due job fires once and is removed`() {
        val fired = mutableListOf<Pair<String, String?>>()
        val s = scheduler()
        s.register("reminder") { id, ctx ->
            fired.add(id to ctx)
            null
        }
        s.schedule("order-1", clock.instant().plusSeconds(60), "reminder", """{"n":1}""")

        s.fire()
        assertTrue(fired.isEmpty(), "not due yet")

        clock.advance(Duration.ofSeconds(61))
        s.fire()
        assertEquals<List<Pair<String, String?>>>(listOf("order-1" to """{"n":1}"""), fired)
        assertEquals(0, store.size())

        s.fire()
        assertEquals(1, fired.size, "must not fire twice")
    }

    @Test
    fun `job reschedules itself by returning the next fire time`() {
        val fires = AtomicInteger()
        val s = scheduler()
        s.register("recurring") { _, _ ->
            if (fires.incrementAndGet() < 3) clock.instant().plusSeconds(60) else null
        }
        s.schedule("agg", clock.instant().plusSeconds(1), "recurring")

        repeat(3) {
            clock.advance(Duration.ofSeconds(61))
            s.fire()
        }
        assertEquals(3, fires.get())
        assertEquals(0, store.size(), "done after returning null")
    }

    @Test
    fun `failing job is rescheduled after the retry delay`() {
        var failing = true
        val fired = AtomicInteger()
        val s = scheduler()
        s.register("flaky") { _, _ ->
            if (failing) throw IllegalStateException("boom")
            fired.incrementAndGet()
            null
        }
        s.schedule("agg", clock.instant().plusSeconds(1), "flaky")

        clock.advance(Duration.ofSeconds(2))
        s.fire() // fails → rescheduled +5 min
        assertEquals(1, store.size())

        failing = false
        clock.advance(Duration.ofMinutes(4))
        s.fire()
        assertEquals(0, fired.get(), "retry not yet due")

        clock.advance(Duration.ofMinutes(2))
        s.fire()
        assertEquals(1, fired.get())
    }

    @Test
    fun `unregistered job type is rescheduled, not lost`() {
        val s = scheduler()
        s.schedule("agg", clock.instant().plusSeconds(1), "nobody-registered-this")
        clock.advance(Duration.ofSeconds(2))
        s.fire()
        assertEquals(1, store.size(), "entry must survive until a job is registered")
    }

    @Test
    fun `scheduling again moves the fire time (upsert identity)`() {
        val fired = AtomicInteger()
        val s = scheduler()
        s.register("reminder") { _, _ ->
            fired.incrementAndGet()
            null
        }
        s.schedule("order-1", clock.instant().plusSeconds(60), "reminder")
        s.schedule("order-1", clock.instant().plusSeconds(3600), "reminder") // moved

        clock.advance(Duration.ofSeconds(61))
        s.fire()
        assertEquals(0, fired.get(), "old fire time must not apply")

        clock.advance(Duration.ofHours(1))
        s.fire()
        assertEquals(1, fired.get())
    }

    @Test
    fun `cancel removes the timer`() {
        val fired = AtomicInteger()
        val s = scheduler()
        s.register("reminder") { _, _ ->
            fired.incrementAndGet()
            null
        }
        s.schedule("order-1", clock.instant().plusSeconds(1), "reminder")
        s.cancel("order-1", "reminder")

        clock.advance(Duration.ofSeconds(2))
        s.fire()
        assertEquals(0, fired.get())
    }

    @Test
    fun `scheduling in the past is rejected`() {
        val s = scheduler()
        s.register("reminder") { _, _ -> null }
        assertFailsWith<IllegalArgumentException> {
            s.schedule("agg", clock.instant().minusSeconds(1), "reminder")
        }
    }

    @Test
    fun `fire pass is skipped while another instance holds the lock`() {
        val fired = AtomicInteger()
        val s = scheduler()
        s.register("reminder") { _, _ ->
            fired.incrementAndGet()
            null
        }
        s.schedule("agg", clock.instant().plusSeconds(1), "reminder")
        clock.advance(Duration.ofSeconds(2))

        assertTrue(lock.tryAcquire(SimpleScheduler.FIRE_LOCK, null), "simulate another instance firing")
        s.fire()
        assertEquals(0, fired.get())

        lock.release(SimpleScheduler.FIRE_LOCK)
        s.fire()
        assertEquals(1, fired.get())
    }

    @Test
    fun `worker pool suspension pauses the scheduler cluster-wide`() {
        val pool = InMemoryWorkerPool(clock)
        val fired = AtomicInteger()
        val s = scheduler { workerPool(pool, "sched") }
        s.register("reminder") { _, _ ->
            fired.incrementAndGet()
            null
        }
        s.schedule("agg", clock.instant().plusSeconds(1), "reminder")
        clock.advance(Duration.ofSeconds(2))

        pool.configure("sched", 0) // operations: suspend
        s.fire()
        assertEquals(0, fired.get())

        pool.configure("sched", 1) // operations: resume
        s.fire()
        assertEquals(1, fired.get())
    }

    @Test
    fun `entries fire oldest first within a pass`() {
        val order = mutableListOf<String>()
        val s = scheduler()
        s.register("reminder") { id, _ ->
            order.add(id)
            null
        }
        s.schedule("late", clock.instant().plusSeconds(30), "reminder")
        s.schedule("early", clock.instant().plusSeconds(10), "reminder")

        clock.advance(Duration.ofSeconds(31))
        s.fire()
        assertEquals(listOf("early", "late"), order)
    }
}
