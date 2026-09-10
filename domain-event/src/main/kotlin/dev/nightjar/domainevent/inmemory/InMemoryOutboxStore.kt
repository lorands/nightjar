package dev.nightjar.domainevent.inmemory

import dev.nightjar.domainevent.spi.OutboxRecord
import dev.nightjar.domainevent.spi.OutboxStatus
import dev.nightjar.domainevent.spi.OutboxStore
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Heap-backed [OutboxStore] for tests and single-instance applications that
 * can tolerate losing in-flight events on shutdown. Not transactional: appends
 * are immediately visible regardless of the caller's transaction outcome.
 */
public class InMemoryOutboxStore @JvmOverloads constructor(
    private val clock: Clock = Clock.systemUTC(),
) : OutboxStore {

    private val records = ConcurrentHashMap<String, OutboxRecord>()
    private val sequenceLocks = ConcurrentHashMap<String, ReentrantLock>()

    override fun append(record: OutboxRecord) {
        records[record.id] = record
    }

    override fun findPending(limit: Int): List<OutboxRecord> =
        records.values.asSequence()
            .filter { !it.suspended && it.status != OutboxStatus.PROCESSING }
            .sortedBy { it.createdAt }
            .take(limit)
            .toList()

    override fun markSent(id: String) {
        update(id) { it.with(status = OutboxStatus.SENT) }
    }

    override fun claim(id: String): OutboxRecord? {
        var claimed: OutboxRecord? = null
        records.computeIfPresent(id) { _, record ->
            if (record.suspended || record.status == OutboxStatus.PROCESSING) {
                record
            } else {
                record.with(status = OutboxStatus.PROCESSING).also { claimed = it }
            }
        }
        return claimed
    }

    override fun delete(id: String) {
        records.remove(id)
    }

    override fun markError(id: String, error: String) {
        update(id) { it.with(status = OutboxStatus.ERROR, attempts = it.attempts + 1, lastError = error) }
    }

    override fun markSuspended(id: String) {
        update(id) { it.with(suspended = true) }
    }

    override fun resume(id: String) {
        // Back to CREATED: the relay redelivers immediately; if processing fails
        // again on an exhausted record, it re-suspends after that one attempt.
        update(id) { it.with(status = OutboxStatus.CREATED, suspended = false) }
    }

    override fun withSequenceLock(key: String, action: Runnable) {
        sequenceLocks.computeIfAbsent(key) { ReentrantLock() }.withLock { action.run() }
    }

    /** Look up a record by id — for assertions and manual inspection. */
    public fun record(id: String): OutboxRecord? = records[id]

    /** Number of records currently held. */
    public fun size(): Int = records.size

    private fun update(id: String, transform: (OutboxRecord) -> OutboxRecord) {
        records.computeIfPresent(id) { _, record -> transform(record) }
    }

    private fun OutboxRecord.with(
        status: OutboxStatus = this.status,
        attempts: Int = this.attempts,
        suspended: Boolean = this.suspended,
        lastError: String? = this.lastError,
    ): OutboxRecord = OutboxRecord(
        id, eventType, payload, metadata, sequenceKey,
        status, attempts, createdAt, clock.instant(), suspended, lastError,
    )
}
