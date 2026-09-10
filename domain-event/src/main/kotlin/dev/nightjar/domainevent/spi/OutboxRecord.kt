package dev.nightjar.domainevent.spi

import java.time.Instant

/** Lifecycle states of an outbox record. Successful records are deleted, not stored. */
public enum class OutboxStatus {
    /** Appended in the publishing transaction, not yet handed to the transport. */
    CREATED,

    /** Handed to the transport; awaiting consumption. */
    SENT,

    /** Claimed by a consumer; listeners are running. */
    PROCESSING,

    /** Listener processing failed; eligible for retry per the retry policy. */
    ERROR,
}

/**
 * A persisted event awaiting delivery — one row in the outbox store.
 *
 * The outbox is transient reliability storage, not an event store: records are
 * deleted once every listener has processed them successfully.
 */
public class OutboxRecord(
    public val id: String,
    public val eventType: String,
    public val payload: ByteArray,
    public val metadata: Map<String, String>,
    public val sequenceKey: String?,
    public val status: OutboxStatus,
    /** Number of failed processing attempts so far. */
    public val attempts: Int,
    public val createdAt: Instant,
    /** Last status change; null until the first transition out of [OutboxStatus.CREATED]. */
    public val modifiedAt: Instant?,
    /** Suspended records are skipped by the relay until manually resumed. */
    public val suspended: Boolean,
    /** Stack trace of the most recent processing failure. */
    public val lastError: String?,
) {

    override fun toString(): String =
        "OutboxRecord(id=$id, eventType=$eventType, status=$status, attempts=$attempts, suspended=$suspended)"
}
