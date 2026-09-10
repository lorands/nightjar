package dev.nightjar.migrations.jdbc

import org.h2.jdbcx.JdbcDataSource
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** Fresh in-memory H2 (PostgreSQL mode) with the data-migration schema applied. */
object H2Databases {

    private val counter = AtomicInteger()

    fun create(): DataSource {
        val dataSource = JdbcDataSource()
        dataSource.setURL(
            "jdbc:h2:mem:nightjar-mig${counter.incrementAndGet()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        )
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                // PostgreSQL DDL minus partial indexes (H2 doesn't support WHERE on indexes)
                statement.execute(
                    """
                    CREATE TABLE data_migration (
                        id         VARCHAR(100) NOT NULL,
                        type       VARCHAR(100) NOT NULL,
                        priority   INT          NOT NULL DEFAULT 100,
                        attempts   INT          NOT NULL DEFAULT 0,
                        claimed_by VARCHAR(64),
                        claimed_at TIMESTAMP
                    )
                    """.trimIndent(),
                )
                statement.execute("CREATE INDEX idx_data_migration_claimable ON data_migration (priority, type)")
                statement.execute(
                    """
                    CREATE TABLE sequential_data_migration (
                        id        VARCHAR(100) NOT NULL,
                        type      VARCHAR(100) NOT NULL,
                        row_index BIGINT       NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute("CREATE INDEX idx_sequential_data_migration_order ON sequential_data_migration (row_index)")
                statement.execute("CREATE TABLE data_migration_lock (lock_key VARCHAR(250) PRIMARY KEY)")
            }
        }
        return dataSource
    }
}
