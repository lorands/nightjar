package dev.nightjar.domainevent.rabbitmq

import com.github.fridujo.rabbitmq.mock.MockConnectionFactory
import dev.nightjar.domainevent.spi.TransportMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RabbitMqEventTransportTest {

    private val factory = MockConnectionFactory()
    private var transport: RabbitMqEventTransport? = null

    private fun transport(): RabbitMqEventTransport =
        RabbitMqEventTransport(factory, "test.domain.events").also { transport = it }

    @AfterTest
    fun tearDown() {
        transport?.close()
    }

    @Test
    fun `sends and receives a message with id, type, payload and metadata`() {
        val received = AtomicReference<TransportMessage>()
        val latch = CountDownLatch(1)
        val t = transport()
        t.startConsuming { message ->
            received.set(message)
            latch.countDown()
        }

        t.send(TransportMessage("evt-1", "OrderPlaced", byteArrayOf(4, 5, 6), mapOf("user" to "lori")))

        assertTrue(latch.await(5, TimeUnit.SECONDS), "message was not delivered")
        val message = received.get()
        assertEquals("evt-1", message.eventId)
        assertEquals("OrderPlaced", message.eventType)
        assertContentEquals(byteArrayOf(4, 5, 6), message.payload)
        assertEquals("lori", message.metadata["user"])
    }

    @Test
    fun `messages are delivered in send order`() {
        val order = mutableListOf<String>()
        val latch = CountDownLatch(3)
        val t = transport()
        t.startConsuming { message ->
            synchronized(order) { order.add(message.eventId) }
            latch.countDown()
        }

        t.send(TransportMessage("a", "T", ByteArray(0), emptyMap()))
        t.send(TransportMessage("b", "T", ByteArray(0), emptyMap()))
        t.send(TransportMessage("c", "T", ByteArray(0), emptyMap()))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test
    fun `crashing handler dead-letters the message to the poison queue`() {
        val latch = CountDownLatch(1)
        val t = transport()
        t.startConsuming { throw IllegalStateException("handler bug") }

        // observe the poison queue directly
        val poisonBody = AtomicReference<ByteArray>()
        factory.newConnection().use { connection ->
            connection.createChannel().use { channel ->
                channel.basicConsume(
                    "test.domain.events.poison",
                    true,
                    { _, delivery ->
                        poisonBody.set(delivery.body)
                        latch.countDown()
                    },
                    { _ -> },
                )

                t.send(TransportMessage("evt-poison", "Broken", byteArrayOf(9), emptyMap()))

                assertTrue(latch.await(5, TimeUnit.SECONDS), "message never reached the poison queue")
                assertContentEquals(byteArrayOf(9), poisonBody.get())
            }
        }
    }
}
