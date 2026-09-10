package dev.nightjar.coordination.worker

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Heap-backed [WorkerPool] for tests and single-instance applications.
 * Permits expire after [permitTtl] (checked on acquisition — no background
 * thread needed in-memory).
 */
public class InMemoryWorkerPool @JvmOverloads constructor(
    private val clock: Clock = Clock.systemUTC(),
    private val permitTtl: Duration = Duration.ofMinutes(10),
    private val defaultConcurrency: Int = 1,
) : WorkerPool {

    private class Permit(val type: String, val created: Instant)

    private val monitor = Any()
    private val permits = ConcurrentHashMap<String, Permit>()
    private val concurrency = ConcurrentHashMap<String, Int>()

    override fun newWorker(type: String): String? {
        synchronized(monitor) {
            val cutoff = clock.instant().minus(permitTtl)
            permits.values.removeIf { it.created.isBefore(cutoff) } // inline self-heal
            val cap = concurrency.getOrDefault(type, defaultConcurrency)
            val held = permits.values.count { it.type == type }
            if (held >= cap) return null
            val uuid = UUID.randomUUID().toString()
            permits[uuid] = Permit(type, clock.instant())
            return uuid
        }
    }

    override fun terminate(uuid: String) {
        permits.remove(uuid)
    }

    override fun configure(type: String, concurrency: Int) {
        require(concurrency >= 0) { "concurrency must be >= 0" }
        this.concurrency[type] = concurrency
    }

    override fun isSuspended(type: String): Boolean =
        concurrency.getOrDefault(type, defaultConcurrency) == 0

    override fun close() {
        // no background resources in-memory
    }
}
