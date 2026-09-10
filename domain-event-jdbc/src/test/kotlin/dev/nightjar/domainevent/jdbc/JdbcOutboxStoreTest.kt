package dev.nightjar.domainevent.jdbc

import dev.nightjar.domainevent.spi.OutboxRecord
import dev.nightjar.domainevent.spi.OutboxStatus
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcOutboxStoreTest {

    private lateinit var store: JdbcOutboxStore

    @BeforeTest
    fun setUp() {
        store = JdbcOutboxStore(H2Databases.create())
    }

    private fun record(
        id: String = "evt-1",
        sequenceKey: String? = "order-42",
    ) = OutboxRecord(
        id = id,
        eventType = "OrderPlaced",
        payload = byteArrayOf(1, 2, 3),
        metadata = mapOf("user" to "lori", "weird key" to "a=b\nc"),
        sequenceKey = sequenceKey,
        status = OutboxStatus.CREATED,
        attempts = 0,
        createdAt = Instant.parse("2026-06-04T10:00:00Z").truncatedTo(ChronoUnit.MILLIS),
        modifiedAt = null,
        suspended = false,
        lastError = null,
    )

    @Test
    fun `append and findPending round-trips all fields`() {
        store.append(record())

        val loaded = store.findPending(10).single()
        assertEquals("evt-1", loaded.id)
        assertEquals("OrderPlaced", loaded.eventType)
        assertContentEquals(byteArrayOf(1, 2, 3), loaded.payload)
        assertEquals(mapOf("user" to "lori", "weird key" to "a=b\nc"), loaded.metadata)
        assertEquals("order-42", loaded.sequenceKey)
        assertEquals(OutboxStatus.CREATED, loaded.status)
        assertEquals(0, loaded.attempts)
        assertEquals(Instant.parse("2026-06-04T10:00:00Z"), loaded.createdAt)
        assertNull(loaded.modifiedAt)
        assertEquals(false, loaded.suspended)
        assertNull(loaded.lastError)
    }

    @Test
    fun `findPending orders by creation and respects the limit`() {
        store.append(record(id = "b").let { OutboxRecord(it.id, it.eventType, it.payload, it.metadata, null, it.status, 0, Instant.parse("2026-06-04T10:00:02Z"), null, false, null) })
        store.append(record(id = "a").let { OutboxRecord(it.id, it.eventType, it.payload, it.metadata, null, it.status, 0, Instant.parse("2026-06-04T10:00:01Z"), null, false, null) })

        assertEquals(listOf("a"), store.findPending(1).map { it.id })
        assertEquals(listOf("a", "b"), store.findPending(10).map { it.id })
    }

    @Test
    fun `claim is exclusive and idempotent-safe`() {
        store.append(record())

        val claimed = assertNotNull(store.claim("evt-1"))
        assertEquals(OutboxStatus.PROCESSING, claimed.status)
        assertNotNull(claimed.modifiedAt)

        assertNull(store.claim("evt-1"), "second claim must lose")
        assertNull(store.claim("does-not-exist"))
    }

    @Test
    fun `claim refuses suspended records`() {
        store.append(record())
        store.markSuspended("evt-1")

        assertNull(store.claim("evt-1"))
    }

    @Test
    fun `markError increments attempts and stores the error`() {
        store.append(record())
        store.markError("evt-1", "boom-1")
        store.markError("evt-1", "boom-2")

        val loaded = store.findPending(10).single()
        assertEquals(OutboxStatus.ERROR, loaded.status)
        assertEquals(2, loaded.attempts)
        assertEquals("boom-2", loaded.lastError)
    }

    @Test
    fun `suspended records disappear from findPending until resumed`() {
        store.append(record())
        store.markSuspended("evt-1")
        assertEquals(0, store.findPending(10).size)

        store.resume("evt-1")
        val resumed = store.findPending(10).single()
        assertEquals(OutboxStatus.CREATED, resumed.status)
        assertEquals(false, resumed.suspended)
    }

    @Test
    fun `processing records are not pending`() {
        store.append(record())
        store.claim("evt-1")
        assertEquals(0, store.findPending(10).size)
    }

    @Test
    fun `delete removes the record`() {
        store.append(record())
        store.delete("evt-1")
        assertEquals(0, store.findPending(10).size)
    }

    @Test
    fun `markSent transitions status`() {
        store.append(record())
        store.markSent("evt-1")
        assertEquals(OutboxStatus.SENT, store.findPending(10).single().status)
    }

    @Test
    fun `sequence lock is mutually exclusive across threads`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val firstInside = CountDownLatch(1)

        val first = thread {
            store.withSequenceLock("order-42") {
                firstInside.countDown()
                order.add("first-start")
                Thread.sleep(300)
                order.add("first-end")
            }
        }
        assertTrue(firstInside.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val second = thread {
            store.withSequenceLock("order-42") { order.add("second") }
        }

        first.join(5_000)
        second.join(5_000)
        assertEquals(listOf("first-start", "first-end", "second"), order)
    }
}
