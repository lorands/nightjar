package dev.nightjar.domainevent.jdbc

import dev.nightjar.domainevent.spi.OutboxRecord
import dev.nightjar.domainevent.spi.OutboxStatus
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import javax.sql.DataSource

/**
 * The outbox store contract against a real PostgreSQL —
 * `devbox services up` provides it; skipped when not running.
 */
class PostgresOutboxStoreIT {

    private lateinit var store: JdbcOutboxStore

    @BeforeTest
    fun setUp() {
        val dataSource = try {
            freshDatabase()
        } catch (e: Exception) {
            assumeTrue(false, "PostgreSQL (port $PG_PORT) unavailable: ${e.message} — start with: devbox services up")
            return
        }
        store = JdbcOutboxStore(dataSource)
    }

    @Test
    fun `round-trips a record through real postgres`() {
        val record = OutboxRecord(
            id = "evt-pg-1",
            eventType = "OrderPlaced",
            payload = byteArrayOf(1, 2, 3),
            metadata = mapOf("user" to "lori"),
            sequenceKey = "order-1",
            status = OutboxStatus.CREATED,
            attempts = 0,
            createdAt = Instant.parse("2026-06-04T10:00:00Z").truncatedTo(ChronoUnit.MILLIS),
            modifiedAt = null,
            suspended = false,
            lastError = null,
        )
        store.append(record)

        val loaded = store.findPending(10).single()
        assertEquals("evt-pg-1", loaded.id)
        assertContentEquals(byteArrayOf(1, 2, 3), loaded.payload)
        assertEquals(mapOf("user" to "lori"), loaded.metadata)
        assertEquals(Instant.parse("2026-06-04T10:00:00Z"), loaded.createdAt)
    }

    @Test
    fun `claim is exclusive on real postgres`() {
        append("evt-pg-2")
        assertNotNull(store.claim("evt-pg-2"))
        assertNull(store.claim("evt-pg-2"))
    }

    @Test
    fun `suspension lifecycle on real postgres`() {
        append("evt-pg-3")
        store.markError("evt-pg-3", "boom")
        store.markSuspended("evt-pg-3")
        assertEquals(0, store.findPending(10).size)

        store.resume("evt-pg-3")
        assertEquals(OutboxStatus.CREATED, store.findPending(10).single().status)
    }

    @Test
    fun `sequence lock excludes concurrent holders on real postgres`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val firstInside = CountDownLatch(1)

        val first = thread {
            store.withSequenceLock("pg-lock") {
                firstInside.countDown()
                order.add("first-start")
                Thread.sleep(300)
                order.add("first-end")
            }
        }
        assertTrue(firstInside.await(5, TimeUnit.SECONDS))
        val second = thread { store.withSequenceLock("pg-lock") { order.add("second") } }

        first.join(5_000)
        second.join(5_000)
        assertEquals(listOf("first-start", "first-end", "second"), order)
    }

    private fun append(id: String) {
        store.append(
            OutboxRecord(
                id, "T", ByteArray(0), emptyMap(), null,
                OutboxStatus.CREATED, 0, Instant.now(), null, false, null,
            ),
        )
    }

    private companion object {

        const val DB = "nightjar_outbox_it"

        // devbox postgres listens on 6543 (no collision with system/docker postgres)
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
                    statement.execute("DROP TABLE IF EXISTS domain_event, domain_event_lock")
                    // the shipped PG schema, exactly as consumers get it
                    val ddl = checkNotNull(
                        PostgresOutboxStoreIT::class.java.classLoader
                            .getResourceAsStream("dev/nightjar/domainevent/jdbc/domain-event-postgresql.sql"),
                    ).use { String(it.readAllBytes()) }
                    statement.execute(ddl)
                }
            }
            return dataSource
        }
    }
}
