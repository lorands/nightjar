package dev.nightjar.spring

import dev.nightjar.domainevent.DomainEventListener
import dev.nightjar.domainevent.DomainEventPublisher
import dev.nightjar.domainevent.EventEnvelope
import dev.nightjar.domainevent.SyncDomainEventListener
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The starter's headline guarantees, exactly as an application experiences
 * them: auto-configured publisher, auto-subscribed listener beans, Jackson
 * round-trip, and outbox atomicity with Spring-managed transactions.
 */
@SpringBootTest(classes = [TestApp::class, DomainEventStarterTest.Listeners::class])
class DomainEventStarterTest(
    private val publisher: DomainEventPublisher,
    private val transactionManager: PlatformTransactionManager,
) {

    companion object {
        val received = AtomicReference<EventEnvelope<OrderPlaced>>()
        val callOrder: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var latch = CountDownLatch(2)
        var syncRan = false
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Listeners {

        @Bean
        @Order(2)
        fun lateListener(): DomainEventListener<OrderPlaced> = DomainEventListener { envelope ->
            callOrder.add("late")
            received.set(envelope)
            latch.countDown()
        }

        @Bean
        @Order(1)
        fun earlyListener(): DomainEventListener<OrderPlaced> = DomainEventListener {
            callOrder.add("early")
            latch.countDown()
        }

        @Bean
        fun syncListener(): SyncDomainEventListener<OrderPlaced> = SyncDomainEventListener {
            syncRan = true
        }
    }

    private fun inTransaction(action: () -> Unit) {
        TransactionTemplate(transactionManager).executeWithoutResult { action() }
    }

    @Test
    fun `publish in a committed transaction delivers through outbox, jackson and ordered listeners`() {
        latch = CountDownLatch(2)
        callOrder.clear()
        syncRan = false

        inTransaction { publisher.publish(OrderPlaced("order-77")) }

        assertTrue(syncRan, "sync listener must run inline during publish")
        assertTrue(latch.await(10, TimeUnit.SECONDS), "async listeners were not invoked")
        assertEquals(listOf("early", "late"), callOrder, "@Order must drive listener order")

        val envelope = received.get()
        assertEquals("order-77", envelope.event.orderId, "Jackson round-trip must preserve the payload")
        assertEquals("OrderPlaced", envelope.type)
    }

    @Test
    fun `publish in a rolled-back transaction is never delivered`() {
        latch = CountDownLatch(2)
        callOrder.clear()

        assertFailsWith<IllegalStateException> {
            inTransaction {
                publisher.publish(OrderPlaced("ghost"))
                throw IllegalStateException("business failure after publish")
            }
        }

        Thread.sleep(400) // ample time for the relay to (incorrectly) pick it up
        assertEquals(2, latch.count, "rolled-back event must not reach listeners")
    }
}
