package dev.nightjar.spring

import dev.nightjar.domainevent.DomainEvent
import dev.nightjar.domainevent.DomainEventListener
import dev.nightjar.domainevent.DomainEventPublisher
import dev.nightjar.domainevent.EventEnvelope
import dev.nightjar.domainevent.spi.EventSerializer
import dev.nightjar.domainevent.spi.MetadataProvider
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * User-defined SPI beans must be honored: an `EventSerializer` backs off the
 * Jackson default, and a `MetadataProvider` populates the envelope metadata.
 */
@SpringBootTest(classes = [TestApp::class, StarterCustomizationTest.Custom::class])
class StarterCustomizationTest(
    private val publisher: DomainEventPublisher,
) {

    companion object {
        val serialized = AtomicInteger()
        val delivered = CountDownLatch(1)
        val receivedMetadata = AtomicReference<Map<String, String>>()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Custom {

        @Bean
        fun customSerializer(): EventSerializer = object : EventSerializer {
            override fun serialize(event: DomainEvent): ByteArray {
                serialized.incrementAndGet()
                return (event as OrderPlaced).orderId.toByteArray()
            }

            override fun deserialize(type: String, payload: ByteArray): DomainEvent =
                OrderPlaced(String(payload))
        }

        /** Stand-in for a security-context reader. */
        @Bean
        fun metadataProvider(): MetadataProvider = MetadataProvider { mapOf("userId" to "user-42") }

        @Bean
        fun listener(): DomainEventListener<OrderPlaced> = DomainEventListener { envelope: EventEnvelope<OrderPlaced> ->
            receivedMetadata.set(envelope.metadata)
            delivered.countDown()
        }
    }

    @Test
    fun `custom serializer and metadata provider beans are honored`() {
        publisher.publish(OrderPlaced("custom"))
        assertTrue(delivered.await(10, TimeUnit.SECONDS))
        assertTrue(serialized.get() >= 1, "custom serializer must have been invoked")
        assertEquals("user-42", receivedMetadata.get()["userId"], "MetadataProvider bean must populate the envelope")
    }
}
