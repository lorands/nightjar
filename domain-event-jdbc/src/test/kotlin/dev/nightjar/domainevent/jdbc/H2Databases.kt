package dev.nightjar.domainevent.jdbc

import org.h2.jdbcx.JdbcDataSource
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** Fresh in-memory H2 database in PostgreSQL compatibility mode, schema applied. */
object H2Databases {

    private val counter = AtomicInteger()

    fun create(): DataSource {
        val dataSource = JdbcDataSource()
        dataSource.setURL(
            "jdbc:h2:mem:nightjar${counter.incrementAndGet()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        )
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                // PostgreSQL DDL minus the partial index (H2 doesn't support WHERE on indexes)
                statement.execute(
                    """
                    CREATE TABLE domain_event (
                        id           VARCHAR(36)  PRIMARY KEY,
                        event_type   VARCHAR(250) NOT NULL,
                        payload      BYTEA        NOT NULL,
                        metadata     TEXT,
                        sequence_key VARCHAR(250),
                        status       VARCHAR(20)  NOT NULL,
                        attempts     INT          NOT NULL DEFAULT 0,
                        created_at   TIMESTAMP    NOT NULL,
                        modified_at  TIMESTAMP,
                        suspended    BOOLEAN      NOT NULL DEFAULT FALSE,
                        last_error   TEXT
                    )
                    """.trimIndent(),
                )
                statement.execute("CREATE INDEX idx_domain_event_pending ON domain_event (created_at)")
                statement.execute("CREATE TABLE domain_event_lock (lock_key VARCHAR(250) PRIMARY KEY)")
            }
        }
        return dataSource
    }
}
