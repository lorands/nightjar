package dev.nightjar.coordination.lock

import dev.nightjar.coordination.MutableClock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryProcessLockTest {

    private val clock = MutableClock()
    private val lock = InMemoryProcessLock(clock)

    @Test
    fun `acquisition is exclusive until released`() {
        assertTrue(lock.tryAcquire("job-1"))
        assertFalse(lock.tryAcquire("job-1"), "second acquire must fail")
        assertTrue(lock.exists("job-1"))

        lock.release("job-1")
        assertFalse(lock.exists("job-1"))
        assertTrue(lock.tryAcquire("job-1"))
    }

    @Test
    fun `locks without ttl never expire`() {
        assertTrue(lock.tryAcquire("eternal"))
        clock.advance(Duration.ofDays(365))
        assertFalse(lock.tryAcquire("eternal"))
        assertTrue(lock.exists("eternal"))
    }

    @Test
    fun `expired lease is taken over atomically`() {
        assertTrue(lock.tryAcquire("leased", Duration.ofMinutes(10)))
        clock.advance(Duration.ofMinutes(5))
        assertFalse(lock.tryAcquire("leased", Duration.ofMinutes(10)), "not yet expired")

        clock.advance(Duration.ofMinutes(6))
        assertFalse(lock.exists("leased"), "expired lock must not report held")
        assertTrue(lock.tryAcquire("leased", Duration.ofMinutes(10)), "crashed holder's lock is reclaimable")
    }

    @Test
    fun `acquire throws when held`() {
        lock.acquire("strict", null)
        assertFailsWith<IllegalStateException> { lock.acquire("strict", null) }
    }

    @Test
    fun `withLock runs and releases, also on exception`() {
        var ran = 0
        assertTrue(lock.withLock("scoped", null) { ran++ })
        assertEquals(1, ran)
        assertFalse(lock.exists("scoped"))

        assertFailsWith<IllegalStateException> {
            lock.withLock("scoped", null) { throw IllegalStateException("boom") }
        }
        assertFalse(lock.exists("scoped"), "lock must be released after exception")
    }

    @Test
    fun `withLock returns false when held elsewhere`() {
        lock.tryAcquire("busy")
        var ran = false
        assertFalse(lock.withLock("busy", null) { ran = true })
        assertFalse(ran)
    }
}
