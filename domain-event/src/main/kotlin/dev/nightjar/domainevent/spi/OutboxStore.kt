package dev.nightjar.domainevent.spi

/**
 * Persistence for in-flight events (transactional outbox).
 *
 * Implementations must be safe for concurrent use by the publishing threads,
 * the relay thread and consumer threads — across multiple application
 * instances sharing one database.
 */
public interface OutboxStore {

    /**
     * Persist a freshly published record (status [OutboxStatus.CREATED]).
     * Must participate in the caller's transaction where the underlying
     * technology supports it, so a rollback also discards the event.
     */
    public fun append(record: OutboxRecord)

    /**
     * Records potentially due for (re)delivery: status `CREATED`, `ERROR` or
     * `SENT`, not suspended, oldest first. The relay decides per record —
     * implementations only pre-filter.
     */
    public fun findPending(limit: Int): List<OutboxRecord>

    /** Mark as handed to the transport ([OutboxStatus.SENT]). */
    public fun markSent(id: String)

    /**
     * Atomically claim a record for processing: transition from any
     * non-`PROCESSING`, non-suspended state to [OutboxStatus.PROCESSING] and
     * return it. Returns `null` if the record is gone (already processed),
     * suspended, or claimed by another consumer — the caller drops the message.
     */
    public fun claim(id: String): OutboxRecord?

    /**
     * Processing succeeded: remove the record permanently. Invoked inside the
     * processing transaction (see [dev.nightjar.domainevent.spi.TransactionalRunner])
     * — implementations must participate in the caller's transaction where the
     * underlying technology supports it, so the record disappears atomically
     * with the listeners' work.
     */
    public fun delete(id: String)

    /** Processing failed: status [OutboxStatus.ERROR], increment attempts, record the error. */
    public fun markError(id: String, error: String)

    /** Stop retrying until [resume]d. Sets `suspended = true`. */
    public fun markSuspended(id: String)

    /** Clear suspension and error state so the relay picks the record up again. */
    public fun resume(id: String)

    /**
     * Run [action] holding an exclusive lock for [key], shared across all
     * application instances using this store. Serializes processing of events
     * with the same [dev.nightjar.domainevent.DomainEvent.sequenceKey].
     */
    public fun withSequenceLock(key: String, action: Runnable)
}
