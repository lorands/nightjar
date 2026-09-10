package dev.nightjar.domainevent.rabbitmq

import com.rabbitmq.client.ConnectionFactory
import dev.nightjar.domainevent.DomainEvent
import dev.nightjar.domainevent.DomainEventBus
import dev.nightjar.domainevent.inmemory.InMemoryOutboxStore
import dev.nightjar.domainevent.spi.EventSerializer
import dev.nightjar.domainevent.spi.TransportMessage
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The transport against a real RabbitMQ broker —
 * `devbox services up` provides it; skipped when not running.
 */
class RabbitMqEventTransportIT {

    private val queue = "nightjar.it.${UUID.randomUUID()}"
    private var transport: RabbitMqEventTransport? = null
    private var bus: DomainEventBus? = null

    @BeforeTest
    fun setUp() {
        try {
            connectionFactory().newConnection("nightjar-it-probe").close()
        } catch (e: Exception) {
            assumeTrue(false, "RabbitMQ (port $AMQP_PORT) unavailable: ${e.message} — start with: devbox services up")
        }
    }

    @AfterTest
    fun tearDown() {
        bus?.close() ?: transport?.close()
    }

    @Test
    fun `round-trips messages through a real broker`() {
        val t = RabbitMqEventTransport(connectionFactory(), queue).also { transport = it }
        val received = AtomicReference<TransportMessage>()
        val latch = CountDownLatch(1)
        t.startConsuming { message ->
            received.set(message)
            latch.countDown()
        }

        t.send(TransportMessage("evt-real-1", "OrderPlaced", byteArrayOf(7, 8), mapOf("k" to "v")))

        assertTrue(latch.await(10, TimeUnit.SECONDS), "message was not delivered by the real broker")
        assertEquals("evt-real-1", received.get().eventId)
        assertEquals("OrderPlaced", received.get().eventType)
        assertContentEquals(byteArrayOf(7, 8), received.get().payload)
        assertEquals("v", received.get().metadata["k"])
    }

    @Test
    fun `full bus runs over a real broker`() {
        class Ping(val n: Int) : DomainEvent

        val serializer = object : EventSerializer {
            override fun serialize(event: DomainEvent) = byteArrayOf((event as Ping).n.toByte())
            override fun deserialize(type: String, payload: ByteArray): DomainEvent = Ping(payload[0].toInt())
        }

        val latch = CountDownLatch(3)
        val received = mutableListOf<Int>()
        val b = DomainEventBus.builder()
            .serializer(serializer)
            .store(InMemoryOutboxStore())
            .transport(RabbitMqEventTransport(connectionFactory(), queue))
            .pollInterval(Duration.ofMillis(50))
            .build()
            .also { bus = it }

        b.subscribe(Ping::class.java) { envelope ->
            synchronized(received) { received.add(envelope.event.n) }
            latch.countDown()
        }
        b.start()

        b.publish(Ping(1))
        b.publish(Ping(2))
        b.publish(Ping(3))

        assertTrue(latch.await(15, TimeUnit.SECONDS), "events were not delivered end-to-end")
        assertEquals(listOf(1, 2, 3), received.sorted())
    }

    private companion object {

        val AMQP_PORT = System.getenv("NIGHTJAR_IT_AMQP_PORT")?.toInt() ?: 5672

        fun connectionFactory(): ConnectionFactory = ConnectionFactory().apply { port = AMQP_PORT }
    }
}
