package dev.nightjar.domainevent.jdbc

import dev.nightjar.domainevent.DomainEvent
import dev.nightjar.domainevent.DomainEventBus
import dev.nightjar.domainevent.TimeWindowRetryPolicy
import dev.nightjar.domainevent.inmemory.InProcessEventTransport
import dev.nightjar.domainevent.spi.EventSerializer
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The full minimalist stack with a real (H2) database: JdbcTransactions +
 * JdbcOutboxStore + DomainEventBus — publish inside a transaction, deliver
 * after it, discard on rollback.
 */
class JdbcDomainEventBusIntegrationTest {

    class OrderPlaced(val orderId: String) : DomainEvent

    object Serializer : EventSerializer {
        override fun serialize(event: DomainEvent): ByteArray = (event as OrderPlaced).orderId.toByteArray()
        override fun deserialize(type: String, payload: ByteArray): DomainEvent {
            require(type == "OrderPlaced") { "Unknown event type: $type" }
            return OrderPlaced(String(payload))
        }
    }

    private val dataSource = H2Databases.create()
    private val transactions = JdbcTransactions(dataSource)
    private val store = JdbcOutboxStore(dataSource, transactions)
    private var bus: DomainEventBus? = null

    private fun bus(): DomainEventBus = DomainEventBus.builder()
        .serializer(Serializer)
        .store(store)
        .transport(InProcessEventTransport())
        .transactionalRunner(transactions)
        .pollInterval(Duration.ofMillis(10))
        .retryPolicy(
            TimeWindowRetryPolicy(
                Duration.ofMillis(50), Duration.ofSeconds(5),
                Duration.ofMillis(50), Duration.ofSeconds(30),
            ),
        )
        .build()
        .also { bus = it }

    @AfterTest
    fun tearDown() {
        bus?.close()
    }

    @Test
    fun `event published in a committed transaction is delivered`() {
        val received = AtomicReference<String>()
        val latch = CountDownLatch(1)
        val b = bus()
        b.subscribe(OrderPlaced::class.java) { envelope ->
            received.set(envelope.event.orderId)
            latch.countDown()
        }
        b.start()

        transactions.run { b.publish(OrderPlaced("order-1")) }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "event was not delivered")
        assertEquals("order-1", received.get())
        awaitUntil("outbox emptied") { store.findPending(10).isEmpty() }
    }

    @Test
    fun `event published in a rolled-back transaction is never delivered`() {
        val latch = CountDownLatch(1)
        val b = bus()
        b.subscribe(OrderPlaced::class.java) { latch.countDown() }
        b.start()

        assertFailsWith<IllegalStateException> {
            transactions.run {
                b.publish(OrderPlaced("ghost-order"))
                throw IllegalStateException("business failure after publish")
            }
        }

        // give the relay ample time to (incorrectly) pick something up
        Thread.sleep(300)
        assertEquals(1, latch.count, "rolled-back event must not reach listeners")
        assertEquals(0, store.findPending(10).size)
    }

    @Test
    fun `a failing listener rolls back sibling listener work and the outbox delete - one transaction per event`() {
        // side-effect table with a PRIMARY KEY: if the first attempt's insert
        // had committed (per-listener transactions), the retry would die on a
        // unique violation and this test would time out
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE side_effect (id VARCHAR(100) PRIMARY KEY)") }
        }
        val attempts = AtomicInteger()
        val done = CountDownLatch(1)
        val b = bus()
        b.subscribe(OrderPlaced::class.java, -1) { envelope ->
            // a listener's own JDBC work joins the single processing transaction
            val connection = checkNotNull(transactions.current()) { "listener must run inside the processing transaction" }
            connection.prepareStatement("INSERT INTO side_effect (id) VALUES (?)").use {
                it.setString(1, envelope.event.orderId)
                it.executeUpdate()
            }
        }
        b.subscribe(OrderPlaced::class.java, 1) {
            if (attempts.incrementAndGet() == 1) throw IllegalStateException("second listener fails on first delivery")
            done.countDown()
        }
        b.start()

        transactions.run { b.publish(OrderPlaced("tx-unit")) }

        assertTrue(done.await(10, TimeUnit.SECONDS), "event was not retried to success")
        assertEquals(2, attempts.get())
        awaitUntil("outbox emptied after the successful retry") { store.findPending(10).isEmpty() }
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM side_effect").use { statement ->
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    assertEquals(1, resultSet.getInt(1), "first attempt's insert must have rolled back with the failing sibling")
                }
            }
        }
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        kotlin.test.fail("Timed out waiting for: $what")
    }
}
