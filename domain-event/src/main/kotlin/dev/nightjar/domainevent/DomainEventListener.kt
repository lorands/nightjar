package dev.nightjar.domainevent

/**
 * Asynchronous event listener: invoked after the publishing transaction, with
 * the event delivered through the configured transport.
 *
 * All listeners of one event run together in a single transaction opened by
 * the configured [dev.nightjar.domainevent.spi.TransactionalRunner], which also
 * covers the outbox delete — a failing listener rolls the whole set back.
 * Delivery is at-least-once: after a crash or a failed attempt every listener
 * of the event is re-invoked — implementations must be idempotent.
 */
public fun interface DomainEventListener<E : DomainEvent> {

    public fun onEvent(envelope: EventEnvelope<E>)
}
