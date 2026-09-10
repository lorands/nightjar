package dev.nightjar.coordination.lock

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Heap-backed [ProcessLock] for tests and single-instance applications.
 */
public class InMemoryProcessLock @JvmOverloads constructor(
    private val clock: Clock = Clock.systemUTC(),
) : ProcessLock {

    private class Held(val acquiredAt: Instant, val ttl: Duration?)

    private val locks = ConcurrentHashMap<String, Held>()

    override fun tryAcquire(id: String, ttl: Duration?): Boolean {
        val now = clock.instant()
        var acquired = false
        locks.compute(id) { _, held ->
            if (held == null || held.isExpired(now)) {
                acquired = true
                Held(now, ttl)
            } else {
                held
            }
        }
        return acquired
    }

    override fun exists(id: String): Boolean =
        locks[id]?.let { !it.isExpired(clock.instant()) } ?: false

    override fun release(id: String) {
        locks.remove(id)
    }

    private fun Held.isExpired(now: Instant): Boolean =
        ttl != null && acquiredAt.plus(ttl).isBefore(now)
}
