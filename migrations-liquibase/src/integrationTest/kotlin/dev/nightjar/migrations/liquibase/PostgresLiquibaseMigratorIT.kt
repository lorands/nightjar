package dev.nightjar.migrations.liquibase

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real thing end-to-end: the migrator discovers the ACTUAL nightjar
 * module manifests (domain-event-jdbc, migrations-jdbc — on the classpath as
 * normal dependencies) and applies their pristine SQL to a real PostgreSQL.
 * `devbox services up` provides it; skipped when not running.
 */
class PostgresLiquibaseMigratorIT {

    private val url = "jdbc:postgresql://localhost:$PG_PORT/$DB"

    @BeforeTest
    fun setUp() {
        try {
            prepareDatabase()
        } catch (e: Exception) {
            assumeTrue(false, "PostgreSQL (port $PG_PORT) unavailable: ${e.message} — start with: devbox services up")
        }
    }

    @Test
    fun `applies all module schemas and is idempotent`() {
        val migrator = LiquibaseMigrator()
        migrator.migrate(url, "postgres", null)

        admin().use { connection ->
            val expectedTables = listOf(
                "domain_event", "domain_event_lock", // domain-event-jdbc
                "data_migration", "sequential_data_migration", "data_migration_lock", // migrations-jdbc
                "process_lock", "worker", "worker_config", "simple_scheduler", // coordination-jdbc
            )
            for (table in expectedTables) {
                assertTrue(tableExists(connection, table), "missing table: $table")
            }
            assertEquals(3, changesetCount(connection), "all module changesets tracked")
        }

        // second run: everything tracked, nothing re-executed
        migrator.migrate(url, "postgres", null)
        admin().use { connection ->
            assertEquals(3, changesetCount(connection))
        }
    }

    @Test
    fun `partial index from pristine sql survives on real postgres`() {
        LiquibaseMigrator().migrate(url, "postgres", null)
        admin().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'domain_event'",
                ).use { resultSet ->
                    val indexes = buildList { while (resultSet.next()) add(resultSet.getString(1)) }
                    assertTrue("idx_domain_event_pending" in indexes, "partial index must exist: $indexes")
                }
            }
        }
    }

    private fun admin(): Connection = DriverManager.getConnection(url, "postgres", null)

    private fun tableExists(connection: Connection, table: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
        ).use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { it.next() }
        }

    private fun changesetCount(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM databasechangelog").use {
                it.next()
                it.getInt(1)
            }
        }

    private fun prepareDatabase() {
        DriverManager.getConnection("jdbc:postgresql://localhost:$PG_PORT/postgres", "postgres", null).use { admin ->
            val exists = admin.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?").use { s ->
                s.setString(1, DB)
                s.executeQuery().use { it.next() }
            }
            if (!exists) admin.createStatement().use { it.execute("CREATE DATABASE $DB") }
        }
        admin().use { connection ->
            connection.createStatement().use {
                it.execute(
                    "DROP TABLE IF EXISTS databasechangelog, databasechangeloglock, " +
                        "domain_event, domain_event_lock, " +
                        "data_migration, sequential_data_migration, data_migration_lock, " +
                        "process_lock, worker, worker_config, simple_scheduler",
                )
            }
        }
    }

    private companion object {

        const val DB = "nightjar_liquibase_it"

        // devbox postgres listens on 6543 (no collision with system/docker postgres)
        val PG_PORT = System.getenv("NIGHTJAR_IT_PG_PORT")?.toInt() ?: 6543
    }
}
