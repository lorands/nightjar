package dev.nightjar.coordination.jdbc

import dev.nightjar.coordination.scheduler.SimpleScheduler
import dev.nightjar.domainevent.jdbc.JdbcTransactions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import javax.sql.DataSource

/**
 * Coordination primitives against a real PostgreSQL —
 * `devbox services up` provides it; skipped when not running.
 */
class PostgresCoordinationIT {

    private lateinit var dataSource: DataSource

    @BeforeTest
    fun setUp() {
        dataSource = try {
            freshDatabase()
        } catch (e: Exception) {
            assumeTrue(false, "PostgreSQL (port $PG_PORT) unavailable: ${e.message} — start with: devbox services up")
            return
        }
    }

    @Test
    fun `lock exclusivity and lease takeover on real postgres`() {
        val a = JdbcProcessLock(dataSource, ownerId = "instance-a")
        val b = JdbcProcessLock(dataSource, ownerId = "instance-b")

        assertTrue(a.tryAcquire("pg-lock", Duration.ofMillis(200)))
        assertFalse(b.tryAcquire("pg-lock", Duration.ofMillis(200)))

        Thread.sleep(300) // lease expires — crashed-holder simulation
        assertTrue(b.tryAcquire("pg-lock", Duration.ofMinutes(5)), "expired lease must be taken over")
        b.release("pg-lock")
    }

    @Test
    fun `lock bound to a transaction frees on rollback and survives a contended acquire on real postgres`() {
        // savepoint-around-INSERT behaviour differs between H2 and PostgreSQL —
        // a real-DB proof that a failed acquire doesn't abort the transaction
        val transactions = JdbcTransactions(dataSource)
        val txLock = JdbcProcessLock(dataSource, transactions)
        val holder = JdbcProcessLock(dataSource, ownerId = "pg-holder")

        assertTrue(holder.tryAcquire("pg-biz"))
        transactions.run {
            assertFalse(txLock.tryAcquire("pg-biz"), "already held")
            assertTrue(txLock.tryAcquire("pg-free"), "transaction still usable after the failed acquire")
        }
        assertTrue(txLock.exists("pg-free"), "committed with the transaction")

        assertFalse(
            runCatching {
                transactions.run {
                    txLock.tryAcquire("pg-rollback")
                    throw IllegalStateException("rollback")
                }
            }.isSuccess,
        )
        assertFalse(txLock.exists("pg-rollback"), "rolled-back transaction released the lock")

        holder.release("pg-biz")
        txLock.release("pg-free")
    }

    @Test
    fun `worker concurrency across two pool instances`() {
        JdbcWorkerPool(dataSource).use { poolA ->
            JdbcWorkerPool(dataSource).use { poolB ->
                poolA.configure("pg-import", 2)
                assertNotNull(poolA.newWorker("pg-import"))
                assertNotNull(poolB.newWorker("pg-import"))
                assertNull(poolA.newWorker("pg-import"), "cap is cluster-wide, not per-instance")
                assertNull(poolB.newWorker("pg-import"))
            }
        }
    }

    @Test
    fun `scheduler fires exactly once with two competing instances`() {
        val fired = AtomicInteger()
        val store = JdbcSchedulerStore(dataSource)
        val job: (String, String?) -> Instant? = { _, _ ->
            fired.incrementAndGet()
            null
        }

        val schedulerA = SimpleScheduler.builder()
            .store(store).lock(JdbcProcessLock(dataSource)).build()
        val schedulerB = SimpleScheduler.builder()
            .store(store).lock(JdbcProcessLock(dataSource)).build()
        schedulerA.use { a ->
            schedulerB.use { b ->
                a.register("pg-job") { id, ctx -> job(id, ctx) }
                b.register("pg-job") { id, ctx -> job(id, ctx) }

                a.schedule("agg-1", Instant.now().plusMillis(50), "pg-job")
                Thread.sleep(100)

                a.fire()
                b.fire()
                assertEquals(1, fired.get(), "due entry must fire exactly once across instances")
            }
        }
    }

    private companion object {

        const val DB = "nightjar_coordination_it"
        val PG_PORT = System.getenv("NIGHTJAR_IT_PG_PORT")?.toInt() ?: 6543

        fun freshDatabase(): DataSource {
            DriverManager.getConnection("jdbc:postgresql://localhost:$PG_PORT/postgres", "postgres", null).use { admin ->
                val exists = admin.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?").use { s ->
                    s.setString(1, DB)
                    s.executeQuery().use { it.next() }
                }
                if (!exists) admin.createStatement().use { it.execute("CREATE DATABASE $DB") }
            }
            val dataSource = PGSimpleDataSource().apply {
                setURL("jdbc:postgresql://localhost:$PG_PORT/$DB")
                user = "postgres"
            }
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TABLE IF EXISTS process_lock, worker, worker_config, simple_scheduler")
                    val ddl = checkNotNull(
                        PostgresCoordinationIT::class.java.classLoader
                            .getResourceAsStream("dev/nightjar/coordination/jdbc/coordination-postgresql.sql"),
                    ).use { String(it.readAllBytes()) }
                    statement.execute(ddl)
                }
            }
            return dataSource
        }
    }
}
