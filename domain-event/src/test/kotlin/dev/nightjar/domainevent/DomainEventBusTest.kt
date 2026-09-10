package dev.nightjar.domainevent

import dev.nightjar.domainevent.inmemory.InMemoryOutboxStore
import dev.nightjar.domainevent.inmemory.InProcessEventTransport
import dev.nightjar.domainevent.spi.EventObserver
import dev.nightjar.domainevent.spi.EventTransport
import dev.nightjar.domainevent.spi.OutboxStatus
import dev.nightjar.domainevent.spi.TransactionalRunner
import dev.nightjar.domainevent.spi.TransportMessage
import dev.nightjar.domainevent.spi.TransportMessageHandler
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class DomainEventBusTest {

    private val store = InMemoryOutboxStore()
    private val transport = InProcessEventTransport()
    private var bus: DomainEventBus? = null

    private fun bus(configure: DomainEventBus.Builder.() -> Unit = {}): DomainEventBus =
        DomainEventBus.builder()
            .serializer(TestEventSerializer)
            .store(store)
            .transport(transport)
            .pollInterval(Duration.ofMillis(10))
            .retryPolicy(
                TimeWindowRetryPolicy(
                    Duration.ofMillis(50), Duration.ofSeconds(5),
                    Duration.ofMillis(50), Duration.ofSeconds(30),
                ),
            )
            .apply(configure)
            .build()
            .also { bus = it }

    @AfterTest
    fun tearDown() {
        bus?.close()
    }

    // ------------------------------------------------------------ sync listeners

    @Test
    fun `sync listeners run inline during publish in order`() {
        val calls = mutableListOf<String>()
        val b = bus()
        b.subscribeSync(TestEvent::class.java, 2) { calls.add("second") }
        b.subscribeSync(TestEvent::class.java, 1) { calls.add("first:" + it.event.data) }

        b.publish(TestEvent("hello"))

        // no start(), no waiting: sync dispatch happened on this thread already
        assertEquals(listOf("first:hello", "second"), calls)
        assertEquals(1, store.size())
    }

    @Test
    fun `sync listener failure propagates and prevents the outbox append`() {
        val b = bus()
        b.subscribeSync(TestEvent::class.java) { throw IllegalStateException("domain rule violated") }

        assertFailsWith<IllegalStateException> { b.publish(TestEvent("hello")) }
        assertEquals(0, store.size())
    }

    // ----------------------------------------------------------- async listeners

    @Test
    fun `async listener receives envelope with metadata after start`() {
        val received = AtomicReference<EventEnvelope<TestEvent>>()
        val latch = CountDownLatch(1)
        val b = bus { metadataProvider { mapOf("user" to "lori") } }
        b.subscribe(TestEvent::class.java) { envelope ->
            received.set(envelope)
            latch.countDown()
        }

        b.start()
        b.publish(TestEvent("payload-1"))

        assertTrue(latch.await(5, TimeUnit.SECONDS), "listener was not invoked")
        val envelope = received.get()
        assertEquals("payload-1", envelope.event.data)
        assertEquals("TestEvent", envelope.type)
        assertEquals("lori", envelope.metadata["user"])
        awaitUntil("record deleted after success") { store.size() == 0 }
    }

    @Test
    fun `async listeners run in order, all inside ONE processing transaction`() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val transactions = AtomicInteger()
        val latch = CountDownLatch(2)
        val b = bus {
            transactionalRunner(
                TransactionalRunner { action ->
                    transactions.incrementAndGet()
                    action.run()
                },
            )
        }
        b.subscribe(TestEvent::class.java, 5) {
            calls.add("late")
            latch.countDown()
        }
        b.subscribe(TestEvent::class.java, -5) {
            calls.add("early")
            latch.countDown()
        }

        b.start()
        b.publish(TestEvent("x"))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("early", "late"), calls)
        // one transaction wraps every listener + the delete
        assertEquals(1, transactions.get())
        awaitUntil("record deleted inside that transaction") { store.size() == 0 }
    }

    // ------------------------------------------------------------ processing gate

    @Test
    fun `closed gate pauses delivery and reopening resumes automatically`() {
        val gateOpen = AtomicBoolean(false)
        val delivered = CountDownLatch(1)
        val b = bus { processingGate { gateOpen.get() } }
        b.subscribe(TestEvent::class.java) { delivered.countDown() }
        b.start()

        b.publish(TestEvent("held"))

        Thread.sleep(100) // many relay polls at 10 ms cadence
        assertEquals(1, delivered.count, "no delivery while the gate is closed")
        assertEquals(1, store.size(), "record must stay in the outbox — not suspended, not lost")

        gateOpen.set(true)
        assertTrue(delivered.await(5, TimeUnit.SECONDS), "delivery must resume after the gate reopens")
        awaitUntil("record deleted after processing") { store.size() == 0 }
    }

    @Test
    fun `closed gate drops incoming messages unclaimed`() {
        val gateOpen = AtomicBoolean(true)
        var handler: TransportMessageHandler? = null
        val capturingTransport = object : EventTransport {
            override fun send(message: TransportMessage) {} // never delivers on its own
            override fun startConsuming(handler_: TransportMessageHandler) {
                handler = handler_
            }
            override fun close() {}
        }
        val delivered = CountDownLatch(1)
        val b = DomainEventBus.builder()
            .serializer(TestEventSerializer)
            .store(store)
            .transport(capturingTransport)
            .pollInterval(Duration.ofSeconds(60)) // keep the relay out of the way
            .processingGate { gateOpen.get() }
            .build()
            .also { bus = it }
        b.subscribe(TestEvent::class.java) { delivered.countDown() }
        b.start()
        b.publish(TestEvent("x"))
        val record = store.findPending(1).single()
        val message = TransportMessage(record.id, record.eventType, record.payload, record.metadata)

        gateOpen.set(false)
        handler!!.onMessage(message) // a message from another instance arrives while stopped
        assertEquals(1, delivered.count, "message must be dropped, not processed")
        assertEquals(OutboxStatus.CREATED, store.findPending(1).single().status, "record must stay unclaimed")

        gateOpen.set(true)
        handler!!.onMessage(message)
        assertTrue(delivered.await(5, TimeUnit.SECONDS), "processing must work once the gate reopens")
    }

    // ------------------------------------------------------------------- retries

    @Test
    fun `failed processing is retried until it succeeds`() {
        val attempts = AtomicInteger()
        val failures = AtomicInteger()
        val done = CountDownLatch(1)
        val b = bus {
            observer(object : EventObserver {
                override fun failed(envelope: EventEnvelope<*>, error: Exception) {
                    failures.incrementAndGet()
                }
            })
        }
        b.subscribe(TestEvent::class.java) {
            if (attempts.incrementAndGet() <= 2) throw IllegalStateException("transient failure")
            done.countDown()
        }

        b.start()
        b.publish(TestEvent("retry-me"))

        assertTrue(done.await(10, TimeUnit.SECONDS), "event was not retried to success")
        assertEquals(3, attempts.get())
        assertEquals(2, failures.get())
        awaitUntil("record deleted after eventual success") { store.size() == 0 }
    }

    @Test
    fun `exhausted retries suspend the record`() {
        val publishedId = AtomicReference<String>()
        val suspended = CountDownLatch(1)
        val b = bus {
            retryPolicy(
                TimeWindowRetryPolicy(
                    Duration.ofMillis(30), Duration.ofSeconds(5),
                    Duration.ofMillis(30), Duration.ofMillis(200),
                ),
            )
            observer(object : EventObserver {
                override fun published(envelope: EventEnvelope<*>) {
                    publishedId.set(envelope.id)
                }

                override fun suspended(eventId: String, eventType: String) {
                    suspended.countDown()
                }
            })
        }
        b.subscribe(TestEvent::class.java) { throw IllegalStateException("permanent failure") }

        b.start()
        b.publish(TestEvent("poison"))

        assertTrue(suspended.await(10, TimeUnit.SECONDS), "record was never suspended")
        val record = assertNotNull(store.record(publishedId.get()))
        assertTrue(record.suspended)
        assertEquals(OutboxStatus.ERROR, record.status)
        assertTrue(record.attempts >= 1)
        assertNotNull(record.lastError)
    }

    @Test
    fun `resumed record is redelivered`() {
        val publishedId = AtomicReference<String>()
        val suspended = CountDownLatch(1)
        val healed = CountDownLatch(1)
        val failing = AtomicReference(true)
        val b = bus {
            retryPolicy(
                TimeWindowRetryPolicy(
                    Duration.ofMillis(30), Duration.ofSeconds(5),
                    Duration.ofMillis(30), Duration.ofMillis(200),
                ),
            )
            observer(object : EventObserver {
                override fun published(envelope: EventEnvelope<*>) {
                    publishedId.set(envelope.id)
                }

                override fun suspended(eventId: String, eventType: String) {
                    suspended.countDown()
                }
            })
        }
        b.subscribe(TestEvent::class.java) {
            if (failing.get()) throw IllegalStateException("still broken") else healed.countDown()
        }

        b.start()
        b.publish(TestEvent("fix-me-later"))
        assertTrue(suspended.await(10, TimeUnit.SECONDS))

        failing.set(false) // "deploy the fix", then resume manually
        store.resume(publishedId.get())

        assertTrue(healed.await(10, TimeUnit.SECONDS), "resumed record was not redelivered")
        awaitUntil("record deleted after resume + success") { store.size() == 0 }
    }

    // ----------------------------------------------------------------- edge cases

    @Test
    fun `event without async listeners is dropped with the record removed`() {
        val b = bus()
        b.start()
        b.publish(TestEvent("nobody-cares"))

        awaitUntil("unhandled event cleaned up") { store.size() == 0 }
    }

    /**
     * A consumer can finish before the transport's `send` returns — always with
     * an in-process transport that dispatches inline, and whenever another
     * instance is quicker with a real broker. The relay must not overwrite that
     * outcome: a record reset from ERROR to SENT is not retried on the policy's
     * schedule, it waits for `redeliverAfter` — an hour by default.
     */
    @Test
    fun `a failure landing before send returns still retries on schedule`() {
        val attempts = AtomicInteger()
        val b = bus { transport(InlineEventTransport()) }
        b.subscribe(TestEvent::class.java) {
            attempts.incrementAndGet()
            throw IllegalStateException("always fails")
        }

        b.start()
        b.publish(TestEvent("inline"))

        // Retry interval is 50 ms here, so a healthy relay gets well past three
        // attempts; a clobbered record would stop at one.
        awaitUntil("event retried repeatedly") { attempts.get() >= 3 }
    }

    /** Delivers on the caller's thread, so the consumer always wins the race with the relay. */
    private class InlineEventTransport : EventTransport {
        private val handler = AtomicReference<TransportMessageHandler?>()

        override fun send(message: TransportMessage) {
            handler.get()?.onMessage(message)
        }

        override fun startConsuming(handler: TransportMessageHandler) {
            this.handler.set(handler)
        }

        override fun close() {}
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for: $what")
    }
}
