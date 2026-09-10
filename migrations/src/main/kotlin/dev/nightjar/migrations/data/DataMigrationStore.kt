package dev.nightjar.migrations.data

import java.time.Duration

/** A batch of ids of a single type, claimed exclusively by one engine instance. */
public class ClaimedBatch(
    public val claimToken: String,
    public val type: String,
    public val ids: List<String>,
) {

    override fun toString(): String = "ClaimedBatch(type=$type, size=${ids.size})"
}

/**
 * Persistence for pending data migrations — two work queues:
 *
 * - *parallel*: rows `(id, type, priority, attempts, claim)` — any instance may
 *   claim a batch of one type; priority decides what goes first
 * - *sequential*: rows `(id, type, row_index)` — strictly ordered globally,
 *   processed under a cluster-wide type lock
 *
 * Rows are deleted after successful processing; the queues are transient.
 * Implementations must be safe across threads and application instances.
 */
public interface DataMigrationStore {

    /**
     * Add ids to the parallel queue. Must participate in the caller's
     * transaction where the underlying technology supports it.
     */
    public fun enqueue(type: String, ids: Collection<String>, priority: Int)

    /**
     * Atomically claim up to [batchSize] unclaimed (or expired-claim) rows of
     * the highest-priority pending type not in [excludedTypes]. Returns `null`
     * when nothing is claimable. Claims older than [claimExpiry] count as
     * abandoned (crashed instance) and may be re-claimed.
     *
     * [excludedTypes] carries the types that already failed in the caller's
     * current poll pass — they wait for the next poll instead of spinning.
     */
    public fun claimNextBatch(
        claimToken: String,
        batchSize: Int,
        claimExpiry: Duration,
        excludedTypes: Collection<String>,
    ): ClaimedBatch?

    /** Processing succeeded: remove the batch's rows permanently. */
    public fun deleteClaimed(batch: ClaimedBatch)

    /**
     * Release a claim so the rows become claimable again.
     * [countFailure] increments the rows' attempt counter (diagnostics).
     */
    public fun releaseClaim(batch: ClaimedBatch, countFailure: Boolean)

    /** Append ids to the global sequential queue, in iteration order. */
    public fun enqueueSequential(type: String, ids: Collection<String>)

    /** Type owning the globally smallest pending `row_index`, or `null` if the queue is empty. */
    public fun peekNextSequentialType(): String?

    /** The next up-to-[batchSize] ids of [type] in strict `row_index` order. */
    public fun nextSequentialBatch(type: String, batchSize: Int): List<String>

    /**
     * Sequential batch processed: remove the first [ids]-many head rows of
     * [type] (in `row_index` order) — NOT all rows matching the ids, since the
     * same id may legitimately appear again later in the queue.
     */
    public fun deleteSequential(type: String, ids: Collection<String>)

    /** Run [action] holding a cluster-wide exclusive lock for [type]. */
    public fun withTypeLock(type: String, action: Runnable)
}
