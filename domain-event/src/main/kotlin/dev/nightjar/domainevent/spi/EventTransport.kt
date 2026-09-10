package dev.nightjar.domainevent.spi

/** The wire representation of an event notification. */
public class TransportMessage(
    public val eventId: String,
    public val eventType: String,
    public val payload: ByteArray,
    public val metadata: Map<String, String>,
) {

    override fun toString(): String = "TransportMessage(eventId=$eventId, eventType=$eventType)"
}

/** Receives messages from an [EventTransport]. Implementations never throw. */
public fun interface TransportMessageHandler {

    public fun onMessage(message: TransportMessage)
}

/**
 * Message broker abstraction (RabbitMQ, Kafka, NATS, in-process, ...).
 *
 * Reliability does not depend on the transport: the outbox store is the source
 * of truth and undelivered messages are re-sent by the relay. Transports may
 * therefore be simple — at-most-once delivery is acceptable.
 */
public interface EventTransport : AutoCloseable {

    /** Send a message to this application's event channel. */
    public fun send(message: TransportMessage)

    /** Start delivering incoming messages to [handler]. Called at most once. */
    public fun startConsuming(handler: TransportMessageHandler)

    /** Stop consuming and release broker resources. */
    override fun close()
}
