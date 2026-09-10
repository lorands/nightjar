package dev.nightjar.domainevent

/**
 * Marker interface for domain events.
 *
 * Events are immutable domain values: the infrastructure never mutates them.
 * Identity, timestamp and metadata live in the [EventEnvelope] that wraps the
 * event when it is published.
 */
public interface DomainEvent {

    /**
     * Stable type identifier used on the wire and in the outbox store.
     *
     * Defaults to the simple class name. Override to keep the wire format stable
     * across refactors, or when two event classes share a simple name.
     */
    public fun eventType(): String = javaClass.simpleName

    /**
     * Events sharing the same non-null key are processed sequentially, in
     * publish order. Events with a `null` key (the default) are processed
     * concurrently.
     */
    public fun sequenceKey(): String? = null
}
