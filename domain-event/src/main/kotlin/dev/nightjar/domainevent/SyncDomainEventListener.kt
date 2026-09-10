package dev.nightjar.domainevent

/**
 * Synchronous event listener: invoked inline during
 * [DomainEventPublisher.publish], on the publisher's thread, inside the
 * caller's transaction.
 *
 * An exception thrown here propagates out of `publish` and rolls the whole
 * publishing transaction back (including the outbox append).
 */
public fun interface SyncDomainEventListener<E : DomainEvent> {

    public fun onEvent(envelope: EventEnvelope<E>)
}
