package dev.nightjar.domainevent.spi

import dev.nightjar.domainevent.EventEnvelope

/**
 * Observability hook for tracing, metrics and logging — nightjar bundles no
 * observability framework. All methods default to no-ops; implementations must
 * not throw.
 */
public interface EventObserver {

    /** A new event was published (sync listeners already ran, outbox appended). */
    public fun published(envelope: EventEnvelope<*>) {}

    /** All asynchronous listeners completed; the outbox record was deleted. */
    public fun processed(envelope: EventEnvelope<*>) {}

    /** An asynchronous listener failed; the record was marked for retry. */
    public fun failed(envelope: EventEnvelope<*>, error: Exception) {}

    /** The retry window was exhausted; the record was suspended. */
    public fun suspended(eventId: String, eventType: String) {}

    public companion object {

        /** Observes nothing. */
        @JvmField
        public val NONE: EventObserver = object : EventObserver {}
    }
}
