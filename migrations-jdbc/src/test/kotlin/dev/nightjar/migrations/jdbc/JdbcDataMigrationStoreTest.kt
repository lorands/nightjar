package dev.nightjar.migrations.jdbc

import dev.nightjar.domainevent.jdbc.JdbcTransactions
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import javax.sql.DataSource

class JdbcDataMigrationStoreTest {

    private lateinit var dataSource: DataSource
    private lateinit var store: JdbcDataMigrationStore

    private val expiry = Duration.ofMinutes(10)

    @BeforeTest
    fun setUp() {
        dataSource = H2Databases.create()
        store = JdbcDataMigrationStore(dataSource)
    }

    // ------------------------------------------------------------------ parallel

    @Test
    fun `claims highest-priority type first with batch limit`() {
        store.enqueue("low", listOf("l1", "l2"), 200)
        store.enqueue("high", listOf("h1", "h2", "h3"), 1)

        val first = assertNotNull(store.claimNextBatch("t1", 2, expiry, emptySet()))
        assertEquals("high", first.type)
        assertEquals(listOf("h1", "h2"), first.ids.sorted())

        val second = assertNotNull(store.claimNextBatch("t2", 10, expiry, emptySet()))
        assertEquals("high", second.type)
        assertEquals(listOf("h3"), second.ids)

        val third = assertNotNull(store.claimNextBatch("t3", 10, expiry, emptySet()))
        assertEquals("low", third.type)
    }

    @Test
    fun `excluded types are skipped`() {
        store.enqueue("broken", listOf("b"), 1)
        store.enqueue("fine", listOf("f"), 100)

        val batch = assertNotNull(store.claimNextBatch("t", 10, expiry, setOf("broken")))
        assertEquals("fine", batch.type)
    }

    @Test
    fun `claimed rows are invisible until the claim expires`() {
        store.enqueue("work", listOf("1"), 100)
        assertNotNull(store.claimNextBatch("winner", 10, expiry, emptySet()))
        assertNull(store.claimNextBatch("loser", 10, expiry, emptySet()))
    }

    @Test
    fun `deleteClaimed removes only the claimed batch`() {
        store.enqueue("work", listOf("1", "2", "3"), 100)
        val batch = assertNotNull(store.claimNextBatch("t", 2, expiry, emptySet()))
        store.deleteClaimed(batch)

        val rest = assertNotNull(store.claimNextBatch("t2", 10, expiry, emptySet()))
        assertEquals(1, rest.ids.size)
    }

    @Test
    fun `releaseClaim makes rows claimable again and counts failures`() {
        store.enqueue("work", listOf("1"), 100)
        val batch = assertNotNull(store.claimNextBatch("t", 10, expiry, emptySet()))
        store.releaseClaim(batch, countFailure = true)

        val reclaimed = assertNotNull(store.claimNextBatch("t2", 10, expiry, emptySet()))
        assertEquals(listOf("1"), reclaimed.ids)
        assertEquals(1, attemptsOf("1"))
    }

    @Test
    fun `enqueue joins an active transaction`() {
        val transactions = JdbcTransactions(dataSource)
        val txStore = JdbcDataMigrationStore(dataSource, transactions)

        assertFailsWith<IllegalStateException> {
            transactions.run {
                txStore.enqueue("work", listOf("ghost"), 100)
                throw IllegalStateException("rollback")
            }
        }

        assertNull(store.claimNextBatch("t", 10, expiry, emptySet()), "rolled-back enqueue must leave no rows")
    }

    // ---------------------------------------------------------------- sequential

    @Test
    fun `sequential batches respect strict global order across types`() {
        store.enqueueSequential("alpha", listOf("a1", "a2"))
        store.enqueueSequential("beta", listOf("b1"))
        store.enqueueSequential("alpha", listOf("a3"))

        assertEquals("alpha", store.peekNextSequentialType())
        assertEquals(listOf("a1", "a2"), store.nextSequentialBatch("alpha", 10))

        store.deleteSequential("alpha", listOf("a1", "a2"))
        assertEquals("beta", store.peekNextSequentialType())
        assertEquals(listOf("b1"), store.nextSequentialBatch("beta", 10))

        store.deleteSequential("beta", listOf("b1"))
        assertEquals(listOf("a3"), store.nextSequentialBatch("alpha", 10))
    }

    @Test
    fun `deleteSequential removes only the head run even with duplicate ids`() {
        store.enqueueSequential("alpha", listOf("same-id"))
        store.enqueueSequential("beta", listOf("b1"))
        store.enqueueSequential("alpha", listOf("same-id")) // same id, later again

        store.deleteSequential("alpha", listOf("same-id"))

        assertEquals("beta", store.peekNextSequentialType())
        store.deleteSequential("beta", listOf("b1"))
        assertEquals(listOf("same-id"), store.nextSequentialBatch("alpha", 10), "second occurrence must survive")
    }

    @Test
    fun `type lock is mutually exclusive`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val firstInside = CountDownLatch(1)

        val first = thread {
            store.withTypeLock("alpha") {
                firstInside.countDown()
                order.add("first-start")
                Thread.sleep(300)
                order.add("first-end")
            }
        }
        assertTrue(firstInside.await(5, TimeUnit.SECONDS))
        val second = thread { store.withTypeLock("alpha") { order.add("second") } }

        first.join(5_000)
        second.join(5_000)
        assertEquals(listOf("first-start", "first-end", "second"), order)
    }

    private fun attemptsOf(id: String): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT attempts FROM data_migration WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { it.next(); it.getInt(1) }
            }
        }
}
