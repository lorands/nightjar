package dev.nightjar.compat

import dev.nightjar.domainevent.DomainEvent
import dev.nightjar.domainevent.DomainEventListener
import dev.nightjar.domainevent.DomainEventPublisher
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootApplication
class Boot4App

class PingEvent @JvmOverloads constructor(var n: Int = 0) : DomainEvent

/**
 * The starter (compiled against Boot 3.5) booted on Spring Boot 4:
 * auto-configuration, transaction bridge, listener auto-subscription and the
 * Jackson serializer all working on the next Boot generation.
 */
@SpringBootTest(classes = [Boot4App::class, Boot4CompatTest.Listeners::class])
class Boot4CompatTest(
    private val publisher: DomainEventPublisher,
    private val transactionManager: PlatformTransactionManager,
) {

    companion object {
        val received = AtomicReference<PingEvent>()
        val latch = CountDownLatch(1)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Listeners {

        @Bean
        fun pingListener(): DomainEventListener<PingEvent> = DomainEventListener { envelope ->
            received.set(envelope.event)
            latch.countDown()
        }
    }

    @Test
    fun `starter works end-to-end on spring boot 4`() {
        println("Spring Boot version at runtime: " + org.springframework.boot.SpringBootVersion.getVersion())

        TransactionTemplate(transactionManager).executeWithoutResult {
            publisher.publish(PingEvent(42))
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "event was not delivered on Boot 4")
        assertEquals(42, received.get().n)
        assertTrue(org.springframework.boot.SpringBootVersion.getVersion().startsWith("4."), "must actually run Boot 4")
    }
}
