package dev.nightjar.domainevent.rabbitmq

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DeliverCallback
import dev.nightjar.domainevent.spi.EventTransport
import dev.nightjar.domainevent.spi.TransportMessage
import dev.nightjar.domainevent.spi.TransportMessageHandler
import java.lang.System.Logger.Level

/**
 * [EventTransport] over RabbitMQ using the official low-level Java client —
 * the one optional broker binding; NATS, Kafka etc. fit the same SPI.
 *
 * Topology (declared idempotently on construction):
 * - `<queueName>` — durable event queue; messages are persistent
 * - `<queueName>.poison` — dead-letter target for messages the handler
 *   itself crashes on (the handler normally never throws: processing
 *   failures are retried from the outbox store, not the broker)
 *
 * The transport owns one AMQP connection. Delivery is acked after the
 * handler returns; reliability does not depend on it — lost messages are
 * re-sent by the outbox relay.
 */
public class RabbitMqEventTransport @JvmOverloads constructor(
    connectionFactory: ConnectionFactory,
    private val queueName: String,
    private val prefetchCount: Int = 10,
) : EventTransport {

    private val log = System.getLogger(RabbitMqEventTransport::class.java.name)
    private val poisonQueueName = "$queueName.poison"
    private val connection: Connection = connectionFactory.newConnection("nightjar-domain-event")
    private val sendChannel: Channel = connection.createChannel()

    init {
        sendChannel.queueDeclare(
            queueName,
            true, false, false,
            mapOf(
                "x-dead-letter-exchange" to "",
                "x-dead-letter-routing-key" to poisonQueueName,
            ),
        )
        sendChannel.queueDeclare(poisonQueueName, true, false, false, null)
    }

    override fun send(message: TransportMessage) {
        val properties = AMQP.BasicProperties.Builder()
            .deliveryMode(2) // persistent
            .messageId(message.eventId)
            .type(message.eventType)
            .headers(message.metadata.toMap())
            .build()
        synchronized(sendChannel) {
            sendChannel.basicPublish("", queueName, properties, message.payload)
        }
    }

    override fun startConsuming(handler: TransportMessageHandler) {
        val channel = connection.createChannel()
        channel.basicQos(prefetchCount)
        channel.basicConsume(
            queueName,
            false, // manual ack
            DeliverCallback { _, delivery ->
                val eventId = delivery.properties.messageId
                val eventType = delivery.properties.type
                if (eventId == null || eventType == null) {
                    log.log(Level.WARNING, "Discarding foreign message without id/type from $queueName")
                    channel.basicReject(delivery.envelope.deliveryTag, false)
                    return@DeliverCallback
                }
                try {
                    handler.onMessage(
                        TransportMessage(eventId, eventType, delivery.body, headersToMetadata(delivery.properties.headers)),
                    )
                    channel.basicAck(delivery.envelope.deliveryTag, false)
                } catch (e: Exception) {
                    // Handlers don't throw in normal operation — this is a bug or
                    // infrastructure failure. Dead-letter instead of redelivery-looping.
                    log.log(Level.ERROR, "Handler crashed for event $eventId — dead-lettering to $poisonQueueName", e)
                    channel.basicReject(delivery.envelope.deliveryTag, false)
                }
            },
            CancelCallback { },
        )
    }

    override fun close() {
        connection.close()
    }

    private fun headersToMetadata(headers: Map<String, Any?>?): Map<String, String> =
        headers?.mapValues { (_, value) -> value.toString() } ?: emptyMap()
}
