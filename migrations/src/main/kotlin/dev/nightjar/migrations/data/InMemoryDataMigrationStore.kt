package dev.nightjar.migrations.data

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Heap-backed [DataMigrationStore] for tests and single-instance applications
 * that can tolerate losing the queues on shutdown. Not transactional.
 */
public class InMemoryDataMigrationStore @JvmOverloads constructor(
    private val clock: Clock = Clock.systemUTC(),
) : DataMigrationStore {

    private class Row(
        val id: String,
        val type: String,
        val priority: Int,
        var attempts: Int = 0,
        var claimedBy: String? = null,
        var claimedAt: Instant? = null,
    )

    private class SequentialRow(val id: String, val type: String, val rowIndex: Long)

    private val monitor = Any()
    private val rows = mutableListOf<Row>()
    private val sequentialRows = mutableListOf<SequentialRow>()
    private var nextRowIndex = 0L
    private val typeLocks = ConcurrentHashMap<String, ReentrantLock>()

    override fun enqueue(type: String, ids: Collection<String>, priority: Int) {
        synchronized(monitor) {
            ids.forEach { rows.add(Row(it, type, priority)) }
        }
    }

    override fun claimNextBatch(
        claimToken: String,
        batchSize: Int,
        claimExpiry: Duration,
        excludedTypes: Collection<String>,
    ): ClaimedBatch? {
        synchronized(monitor) {
            val now = clock.instant()
            val expiryCutoff = now.minus(claimExpiry)
            val excluded = excludedTypes.toSet()
            val claimable = rows.filter {
                it.type !in excluded && (it.claimedBy == null || it.claimedAt!!.isBefore(expiryCutoff))
            }
            val first = claimable.minWithOrNull(compareBy({ it.priority }, { it.type })) ?: return null
            val batch = claimable.filter { it.type == first.type }.take(batchSize)
            batch.forEach {
                it.claimedBy = claimToken
                it.claimedAt = now
            }
            return ClaimedBatch(claimToken, first.type, batch.map { it.id })
        }
    }

    override fun deleteClaimed(batch: ClaimedBatch) {
        synchronized(monitor) {
            rows.removeAll { it.claimedBy == batch.claimToken }
        }
    }

    override fun releaseClaim(batch: ClaimedBatch, countFailure: Boolean) {
        synchronized(monitor) {
            rows.filter { it.claimedBy == batch.claimToken }.forEach {
                it.claimedBy = null
                it.claimedAt = null
                if (countFailure) it.attempts++
            }
        }
    }

    override fun enqueueSequential(type: String, ids: Collection<String>) {
        synchronized(monitor) {
            ids.forEach { sequentialRows.add(SequentialRow(it, type, nextRowIndex++)) }
        }
    }

    override fun peekNextSequentialType(): String? =
        synchronized(monitor) { sequentialRows.minByOrNull { it.rowIndex }?.type }

    override fun nextSequentialBatch(type: String, batchSize: Int): List<String> =
        synchronized(monitor) {
            // strict global order: only the contiguous head run of this type
            sequentialRows.sortedBy { it.rowIndex }
                .takeWhile { it.type == type }
                .take(batchSize)
                .map { it.id }
        }

    override fun deleteSequential(type: String, ids: Collection<String>) {
        synchronized(monitor) {
            // head-run semantics: remove the first ids.size rows of this type
            val head = sequentialRows.filter { it.type == type }
                .sortedBy { it.rowIndex }
                .take(ids.size)
            sequentialRows.removeAll(head.toSet())
        }
    }

    override fun withTypeLock(type: String, action: Runnable) {
        typeLocks.computeIfAbsent(type) { ReentrantLock() }.withLock { action.run() }
    }

    /** Pending parallel rows — for assertions and inspection. */
    public fun pendingCount(): Int = synchronized(monitor) { rows.size }

    /** Pending sequential rows — for assertions and inspection. */
    public fun sequentialPendingCount(): Int = synchronized(monitor) { sequentialRows.size }

    /** Attempt counter of a parallel row — for assertions and inspection. */
    public fun attemptsOf(id: String): Int? = synchronized(monitor) { rows.find { it.id == id }?.attempts }
}
