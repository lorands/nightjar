package dev.nightjar.coordination.worker

import dev.nightjar.coordination.MutableClock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryWorkerPoolTest {

    private val clock = MutableClock()
    private val pool = InMemoryWorkerPool(clock, permitTtl = Duration.ofMinutes(10))

    @Test
    fun `concurrency limit caps simultaneous workers`() {
        pool.configure("import", 2)
        assertNotNull(pool.newWorker("import"))
        assertNotNull(pool.newWorker("import"))
        assertNull(pool.newWorker("import"), "third worker exceeds the cap")
    }

    @Test
    fun `terminate frees the slot`() {
        pool.configure("import", 1)
        val uuid = assertNotNull(pool.newWorker("import"))
        assertNull(pool.newWorker("import"))

        pool.terminate(uuid)
        assertNotNull(pool.newWorker("import"))
    }

    @Test
    fun `unconfigured types default to concurrency 1`() {
        assertNotNull(pool.newWorker("ad-hoc"))
        assertNull(pool.newWorker("ad-hoc"))
    }

    @Test
    fun `suspension blocks execution`() {
        pool.configure("paused", 0)
        assertTrue(pool.isSuspended("paused"))
        assertNull(pool.newWorker("paused"))

        var ran = false
        assertFalse(pool.execute("paused") { ran = true })
        assertFalse(ran)

        pool.configure("paused", 1)
        assertFalse(pool.isSuspended("paused"))
        assertTrue(pool.execute("paused") { ran = true })
        assertTrue(ran)
    }

    @Test
    fun `execute releases the slot afterwards, also on exception`() {
        pool.configure("safe", 1)
        assertTrue(pool.execute("safe") { })
        kotlin.test.assertFailsWith<IllegalStateException> {
            pool.execute("safe") { throw IllegalStateException("boom") }
        }
        assertNotNull(pool.newWorker("safe"), "slot must be free after exception")
    }

    @Test
    fun `expired permits self-heal`() {
        pool.configure("crashy", 1)
        assertNotNull(pool.newWorker("crashy")) // holder "crashes", never terminates
        assertNull(pool.newWorker("crashy"))

        clock.advance(Duration.ofMinutes(11))
        assertNotNull(pool.newWorker("crashy"), "expired permit must be reclaimable")
    }

    @Test
    fun `types are throttled independently`() {
        pool.configure("a", 1)
        pool.configure("b", 1)
        assertNotNull(pool.newWorker("a"))
        assertNotNull(pool.newWorker("b"))
        assertEquals(null, pool.newWorker("a"))
    }
}
