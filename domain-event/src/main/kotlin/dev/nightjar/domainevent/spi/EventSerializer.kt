package dev.nightjar.domainevent.spi

import dev.nightjar.domainevent.DomainEvent

/**
 * Serializes domain events for the outbox store and the transport.
 *
 * nightjar deliberately ships no JSON library: adapt your serializer of choice
 * (Jackson, kotlinx.serialization, ...) in a few lines. Implementations own the
 * mapping from [DomainEvent.eventType] back to a concrete class — register the
 * types you expect; never deserialize arbitrary class names.
 */
public interface EventSerializer {

    public fun serialize(event: DomainEvent): ByteArray

    /** @throws IllegalArgumentException if [type] is unknown. */
    public fun deserialize(type: String, payload: ByteArray): DomainEvent
}
