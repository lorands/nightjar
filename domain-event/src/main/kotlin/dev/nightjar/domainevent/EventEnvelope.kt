package dev.nightjar.domainevent

import java.time.Instant

/**
 * Immutable wrapper carrying a [DomainEvent] together with its infrastructure
 * identity: id, type, timestamp and contextual metadata.
 *
 * Two envelopes are equal iff their [id]s are equal.
 */
public class EventEnvelope<E : DomainEvent>(
    /** Unique event id (UUIDv7 by default — time-sortable). */
    public val id: String,
    /** Stable type identifier, see [DomainEvent.eventType]. */
    public val type: String,
    /** When the event was published. */
    public val occurredAt: Instant,
    /** Contextual metadata captured at publish time (user id, tenant, trace id, ...). */
    public val metadata: Map<String, String>,
    /** The domain event itself. */
    public val event: E,
) {

    override fun equals(other: Any?): Boolean =
        this === other || (other is EventEnvelope<*> && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "EventEnvelope(id=$id, type=$type, occurredAt=$occurredAt)"
}
