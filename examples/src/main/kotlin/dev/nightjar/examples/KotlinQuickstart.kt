package dev.nightjar.examples

import dev.nightjar.domainevent.DomainEvent
import dev.nightjar.domainevent.DomainEventBus
import dev.nightjar.domainevent.inmemory.InMemoryOutboxStore
import dev.nightjar.domainevent.inmemory.InProcessEventTransport
import dev.nightjar.domainevent.spi.EventSerializer
import java.time.Duration

/** A domain event: an immutable domain value — no infrastructure fields. */
class OrderPlaced(val orderId: String, val amountCents: Long) : DomainEvent

/**
 * Minimal hand-rolled serializer. In a real application adapt your JSON
 * library of choice (Jackson, kotlinx.serialization) in a few lines —
 * nightjar deliberately bundles none.
 */
object ExampleSerializer : EventSerializer {

    override fun serialize(event: DomainEvent): ByteArray = when (event) {
        is OrderPlaced -> "${event.orderId}|${event.amountCents}".toByteArray()
        else -> throw IllegalArgumentException("Unknown event class: ${event.javaClass}")
    }

    override fun deserialize(type: String, payload: ByteArray): DomainEvent = when (type) {
        "OrderPlaced" -> String(payload).split('|').let { OrderPlaced(it[0], it[1].toLong()) }
        else -> throw IllegalArgumentException("Unknown event type: $type")
    }
}

/**
 * Smallest possible setup: no broker, no database. The in-memory store and
 * in-process transport keep full bus semantics (async dispatch, retries),
 * which makes them ideal for tests and simple single-instance services.
 */
object KotlinQuickstart {

    @JvmStatic
    fun main(args: Array<String>) {
        val bus = DomainEventBus.builder()
            .serializer(ExampleSerializer)
            .store(InMemoryOutboxStore())
            .transport(InProcessEventTransport())
            .pollInterval(Duration.ofMillis(20))
            .build()

        // Sync: runs inline during publish, inside the publisher's transaction
        bus.subscribeSync(OrderPlaced::class.java) { envelope ->
            println("[sync ] order ${envelope.event.orderId} for ${envelope.event.amountCents} cents")
        }

        // Async: runs after publish, delivered through the transport
        bus.subscribe(OrderPlaced::class.java) { envelope ->
            println("[async] order ${envelope.event.orderId} (event ${envelope.id} at ${envelope.occurredAt})")
        }

        bus.start()
        bus.use {
            it.publish(OrderPlaced("order-1", 4_999))
            Thread.sleep(300) // demo only: give the async side time before shutdown
        }
    }
}
