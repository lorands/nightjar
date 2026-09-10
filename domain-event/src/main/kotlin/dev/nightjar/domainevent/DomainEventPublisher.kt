package dev.nightjar.domainevent

/**
 * Publishes domain events.
 *
 * Call inside the application's transaction: synchronous listeners run inline
 * (and roll back with the caller), and the outbox append joins the same
 * transaction when the configured store supports it.
 */
public interface DomainEventPublisher {

    public fun publish(event: DomainEvent)
}
