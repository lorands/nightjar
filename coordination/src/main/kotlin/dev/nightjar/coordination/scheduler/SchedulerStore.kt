package dev.nightjar.coordination.scheduler

import java.time.Instant

/** One persisted timer: a job of [jobType] for [aggregateId], due at [fireAfter]. */
public class ScheduledEntry(
    public val aggregateId: String,
    public val jobType: String,
    public val fireAfter: Instant,
    /** Opaque, application-owned payload (serialize however you like). */
    public val context: String?,
) {

    override fun toString(): String = "ScheduledEntry($aggregateId, $jobType, fireAfter=$fireAfter)"
}

/**
 * Persistence for scheduled timers. `(aggregateId, jobType)` is the identity:
 * scheduling again overwrites the fire time.
 *
 * Implementations must be safe across threads and application instances.
 */
public interface SchedulerStore {

    /**
     * Insert or update the entry. Must participate in the caller's transaction
     * where the underlying technology supports it — scheduling is atomic with
     * the business change.
     */
    public fun upsert(entry: ScheduledEntry)

    /** Remove an entry. Silently ignores unknown keys. */
    public fun delete(aggregateId: String, jobType: String)

    /** Entries due at [now], oldest `fireAfter` first, at most [limit]. */
    public fun due(now: Instant, limit: Int): List<ScheduledEntry>
}
