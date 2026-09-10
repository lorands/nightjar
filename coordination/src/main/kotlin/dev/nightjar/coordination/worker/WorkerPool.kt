package dev.nightjar.coordination.worker

/**
 * Cross-instance concurrency throttle: a worker
 * is a claimed execution slot of a *worker type*; the type's configured
 * concurrency caps how many slots may be held cluster-wide at once.
 *
 * - `configure(type, 0)` **suspends** the type: no new workers, [execute] skips
 * - permits expire after a TTL (default 10 min) so crashed holders self-heal
 * - types without explicit configuration default to concurrency 1
 *
 * Not a thread pool: tasks run on the caller's thread; the pool only decides
 * *whether* they may run.
 */
public interface WorkerPool : AutoCloseable {

    /**
     * Try to claim a worker slot for [type]. Returns the worker's id, or
     * `null` when the type is at capacity or suspended. Callers must
     * [terminate] the worker when done.
     */
    public fun newWorker(type: String): String?

    /** Release a claimed worker slot. Silently ignores unknown ids. */
    public fun terminate(uuid: String)

    /**
     * Convenience: claim a slot, run [task] on the calling thread, release.
     * Returns `false` without running when no slot is available.
     */
    public fun execute(type: String, task: Runnable): Boolean {
        val uuid = newWorker(type) ?: return false
        try {
            task.run()
        } finally {
            terminate(uuid)
        }
        return true
    }

    /** Set the cluster-wide concurrency cap for [type]. `0` suspends it. */
    public fun configure(type: String, concurrency: Int)

    /** Is [type] suspended (concurrency configured to 0)? */
    public fun isSuspended(type: String): Boolean

    /** Stop background permit cleanup. */
    override fun close()
}
