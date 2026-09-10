package dev.nightjar.coordination.jdbc

import dev.nightjar.coordination.scheduler.ScheduledEntry
import dev.nightjar.coordination.scheduler.SimpleScheduler
import dev.nightjar.domainevent.jdbc.JdbcTransactions
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import javax.sql.DataSource

class JdbcCoordinationTest {

    private lateinit var dataSource: DataSource
    private lateinit var clock: MutableClock

    @BeforeTest
    fun setUp() {
        dataSource = H2Databases.create()
        clock = MutableClock()
    }

    // -------------------------------------------------------------- ProcessLock

    @Test
    fun `lock acquisition is exclusive across instances`() {
        val instanceA = JdbcProcessLock(dataSource, clock = clock, ownerId = "a")
        val instanceB = JdbcProcessLock(dataSource, clock = clock, ownerId = "b")

        assertTrue(instanceA.tryAcquire("shared"))
        assertFalse(instanceB.tryAcquire("shared"))
        assertTrue(instanceB.exists("shared"))

        instanceA.release("shared")
        assertTrue(instanceB.tryAcquire("shared"))
    }

    @Test
    fun `expired lease is taken over, eternal locks are not`() {
        val lock = JdbcProcessLock(dataSource, clock = clock)
        assertTrue(lock.tryAcquire("leased", Duration.ofMinutes(10)))
        assertTrue(lock.tryAcquire("eternal"))

        clock.advance(Duration.ofMinutes(11))
        assertFalse(lock.exists("leased"), "expired lease must not report held")
        assertTrue(lock.tryAcquire("leased", Duration.ofMinutes(10)), "expired lease is reclaimable")
        assertFalse(lock.tryAcquire("eternal"), "no-ttl lock never expires")
    }

    @Test
    fun `lock joins the caller's transaction - rollback frees it, commit holds it`() {
        val transactions = JdbcTransactions(dataSource)
        val lock = JdbcProcessLock(dataSource, transactions, clock)

        // rollback releases the lock (the INSERT lives and dies with the transaction)
        assertFailsWith<IllegalStateException> {
            transactions.run {
                assertTrue(lock.tryAcquire("biz"))
                assertTrue(lock.exists("biz"), "held within the same transaction")
                throw IllegalStateException("business failure")
            }
        }
        assertFalse(lock.exists("biz"), "a rolled-back transaction must free the lock")

        // commit holds it
        transactions.run { assertTrue(lock.tryAcquire("biz")) }
        assertTrue(lock.exists("biz"), "a committed lock stays held")
    }

    @Test
    fun `contended acquire returns false without poisoning the caller's transaction`() {
        val transactions = JdbcTransactions(dataSource)
        val txLock = JdbcProcessLock(dataSource, transactions, clock)
        val holder = JdbcProcessLock(dataSource, clock = clock, ownerId = "holder")
        assertTrue(holder.tryAcquire("contended")) // held by someone else, autonomously

        transactions.run {
            assertFalse(txLock.tryAcquire("contended"), "already held — acquisition fails")
            // the savepoint rollback kept the transaction usable: a later write still commits
            txLock.tryAcquire("other-lock")
        }
        assertTrue(txLock.exists("other-lock"), "transaction survived the failed acquire and committed")
    }

    @Test
    fun `expired lease is taken over while joining a transaction`() {
        val transactions = JdbcTransactions(dataSource)
        val lock = JdbcProcessLock(dataSource, transactions, clock)
        JdbcProcessLock(dataSource, clock = clock, ownerId = "crashed")
            .tryAcquire("leased", Duration.ofMinutes(10)) // crashed holder, autonomous

        clock.advance(Duration.ofMinutes(11))
        transactions.run {
            assertTrue(lock.tryAcquire("leased", Duration.ofMinutes(10)), "expired lease reclaimable inside a transaction")
        }
        assertTrue(lock.exists("leased"))
    }

    // ---------------------------------------------------------------- WorkerPool

    @Test
    fun `worker concurrency is enforced cluster-wide`() {
        JdbcWorkerPool(dataSource, clock).use { pool ->
            pool.configure("import", 2)
            assertNotNull(pool.newWorker("import"))
            assertNotNull(pool.newWorker("import"))
            assertNull(pool.newWorker("import"))
        }
    }

    @Test
    fun `terminate frees the slot and suspension blocks`() {
        JdbcWorkerPool(dataSource, clock).use { pool ->
            pool.configure("import", 1)
            val uuid = assertNotNull(pool.newWorker("import"))
            assertNull(pool.newWorker("import"))
            pool.terminate(uuid)
            assertNotNull(pool.newWorker("import"))

            pool.configure("import", 0)
            assertTrue(pool.isSuspended("import"))
            assertNull(pool.newWorker("import"))
        }
    }

    @Test
    fun `expired permits self-heal on acquisition`() {
        JdbcWorkerPool(dataSource, clock, permitTtl = Duration.ofMinutes(10)).use { pool ->
            pool.configure("crashy", 1)
            assertNotNull(pool.newWorker("crashy")) // holder crashes, never terminates
            assertNull(pool.newWorker("crashy"))

            clock.advance(Duration.ofMinutes(11))
            assertNotNull(pool.newWorker("crashy"))
        }
    }

    @Test
    fun `unconfigured worker type defaults to concurrency 1`() {
        JdbcWorkerPool(dataSource, clock).use { pool ->
            assertNotNull(pool.newWorker("ad-hoc"))
            assertNull(pool.newWorker("ad-hoc"))
            assertFalse(pool.isSuspended("ad-hoc"))
        }
    }

    // ------------------------------------------------------------ SchedulerStore

    @Test
    fun `upsert overwrites by aggregate and job type`() {
        val store = JdbcSchedulerStore(dataSource)
        store.upsert(ScheduledEntry("agg", "job", Instant.parse("2026-06-05T12:00:00Z"), "v1"))
        store.upsert(ScheduledEntry("agg", "job", Instant.parse("2026-06-05T15:00:00Z"), "v2"))

        val due = store.due(Instant.parse("2026-06-05T16:00:00Z"), 10)
        assertEquals(1, due.size)
        assertEquals("v2", due.single().context)
        assertEquals(Instant.parse("2026-06-05T15:00:00Z"), due.single().fireAfter)
    }

    @Test
    fun `due returns oldest first and respects the limit`() {
        val store = JdbcSchedulerStore(dataSource)
        store.upsert(ScheduledEntry("late", "job", Instant.parse("2026-06-05T12:30:00Z"), null))
        store.upsert(ScheduledEntry("early", "job", Instant.parse("2026-06-05T12:00:00Z"), null))
        store.upsert(ScheduledEntry("future", "job", Instant.parse("2026-06-06T12:00:00Z"), null))

        val due = store.due(Instant.parse("2026-06-05T13:00:00Z"), 10)
        assertEquals(listOf("early", "late"), due.map { it.aggregateId })
        assertEquals(listOf("early"), store.due(Instant.parse("2026-06-05T13:00:00Z"), 1).map { it.aggregateId })
    }

    @Test
    fun `upsert joins an active transaction`() {
        val transactions = JdbcTransactions(dataSource)
        val store = JdbcSchedulerStore(dataSource, transactions)

        assertFailsWith<IllegalStateException> {
            transactions.run {
                store.upsert(ScheduledEntry("ghost", "job", Instant.parse("2026-06-05T12:00:00Z"), null))
                throw IllegalStateException("rollback")
            }
        }
        assertEquals(0, store.due(Instant.parse("2026-06-06T00:00:00Z"), 10).size)
    }

    // ------------------------------------------------- full scheduler over JDBC

    @Test
    fun `scheduler end-to-end over jdbc stores`() {
        val fired = AtomicInteger()
        SimpleScheduler.builder()
            .store(JdbcSchedulerStore(dataSource))
            .lock(JdbcProcessLock(dataSource, clock = clock))
            .clock(clock)
            .build().use { scheduler ->
                scheduler.register("reminder") { aggregateId, context ->
                    assertEquals("order-9", aggregateId)
                    assertEquals("ctx", context)
                    fired.incrementAndGet()
                    null
                }
                scheduler.schedule("order-9", clock.instant().plusSeconds(60), "reminder", "ctx")

                scheduler.fire()
                assertEquals(0, fired.get())

                clock.advance(Duration.ofSeconds(61))
                scheduler.fire()
                assertEquals(1, fired.get())

                scheduler.fire()
                assertEquals(1, fired.get(), "single fire")
            }
    }
}
