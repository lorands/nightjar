package dev.nightjar.migrations.liquibase

import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiquibaseMigratorTest {

    private val counter = AtomicInteger()

    private fun freshUrl() = "jdbc:h2:mem:liqui${counter.incrementAndGet()};DB_CLOSE_DELAY=-1"

    @Test
    fun `applies discovered changelogs with pristine sql`() {
        val url = freshUrl()
        LiquibaseMigrator().migrate(url)

        DriverManager.getConnection(url).use { connection ->
            // the SQL file's table exists and is usable
            connection.createStatement().use {
                it.execute("INSERT INTO liquibase_smoke (id, name) VALUES ('1', 'nightjar')")
            }
            // liquibase tracked the changeset under the module changelog's path
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT filename FROM databasechangelog WHERE id = 'liquibase-test-1'",
                ).use { resultSet ->
                    assertTrue(resultSet.next(), "changeset must be tracked")
                    assertTrue(resultSet.getString(1).contains("liquibase-test-changelog.xml"))
                }
            }
        }
    }

    @Test
    fun `re-running is a no-op`() {
        val url = freshUrl()
        val migrator = LiquibaseMigrator()
        migrator.migrate(url)
        migrator.migrate(url) // second run: tracked changesets are skipped

        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM databasechangelog").use {
                    it.next()
                    assertEquals(1, it.getInt(1))
                }
            }
        }
    }

    @Test
    fun `works over an existing jdbc connection`() {
        val url = freshUrl()
        DriverManager.getConnection(url).use { connection ->
            LiquibaseMigrator().migrate(connection)
            connection.createStatement().use {
                it.execute("INSERT INTO liquibase_smoke (id, name) VALUES ('2', 'connection-based')")
            }
        }
    }
}
