package dev.nightjar.coordination.lock

import java.time.Duration

/**
 * Cross-instance named mutex — the coordination primitive behind sequential
 * processing, single-fire scheduling and any business process that must not
 * run twice concurrently.
 *
 * Semantics:
 * - **non-blocking**: acquisition either succeeds immediately or fails
 * - **non-reentrant**: acquiring a held lock fails, even for the same caller
 * - **explicit release** — plus an optional per-acquire [Duration] lease:
 *   a lock whose TTL elapsed counts as abandoned (crashed holder) and is
 *   atomically taken over by the next acquirer. `null` TTL = never expires.
 */
public interface ProcessLock {

    /** Try to acquire [id] with an optional lease [ttl]. `true` if acquired. */
    public fun tryAcquire(id: String, ttl: Duration?): Boolean

    /** Try to acquire [id] without a lease (never expires). */
    public fun tryAcquire(id: String): Boolean = tryAcquire(id, null)

    /** Acquire or throw [IllegalStateException] if held. */
    public fun acquire(id: String, ttl: Duration?) {
        check(tryAcquire(id, ttl)) { "Process lock '$id' is already held" }
    }

    /** Is [id] currently held (and not expired)? */
    public fun exists(id: String): Boolean

    /** Release [id]. Silently ignores locks that are not held. */
    public fun release(id: String)

    /**
     * Convenience: acquire, run [action], release (also on exception).
     * Returns `false` without running when the lock is held by someone else.
     */
    public fun withLock(id: String, ttl: Duration?, action: Runnable): Boolean {
        if (!tryAcquire(id, ttl)) return false
        try {
            action.run()
        } finally {
            release(id)
        }
        return true
    }
}
