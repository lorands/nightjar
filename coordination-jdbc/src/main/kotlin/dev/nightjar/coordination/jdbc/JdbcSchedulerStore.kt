package dev.nightjar.coordination.jdbc

import dev.nightjar.coordination.scheduler.ScheduledEntry
import dev.nightjar.coordination.scheduler.SchedulerStore
import dev.nightjar.domainevent.jdbc.TransactionalConnectionSource
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * [SchedulerStore] over plain JDBC. [upsert] joins the caller's transaction
 * through [TransactionalConnectionSource] — scheduling commits or rolls back
 * with the business change. Portable SQL (PostgreSQL and H2 verified).
 */
public class JdbcSchedulerStore @JvmOverloads constructor(
    private val dataSource: DataSource,
    private val transactionalConnections: TransactionalConnectionSource = TransactionalConnectionSource.NONE,
    private val tableName: String = "simple_scheduler",
) : SchedulerStore {

    override fun upsert(entry: ScheduledEntry) {
        val transactional = transactionalConnections.current()
        if (transactional != null) {
            upsert(transactional, entry) // joins the caller's transaction
        } else {
            dataSource.connection.use { upsert(it, entry) }
        }
    }

    private fun upsert(connection: Connection, entry: ScheduledEntry) {
        val updated = connection.prepareStatement(
            "UPDATE $tableName SET fire_after = ?, context = ? WHERE aggregate_id = ? AND job_type = ?",
        ).use { statement ->
            statement.setObject(1, entry.fireAfter.toUtc())
            statement.setString(2, entry.context)
            statement.setString(3, entry.aggregateId)
            statement.setString(4, entry.jobType)
            statement.executeUpdate()
        }
        if (updated == 0) {
            try {
                connection.prepareStatement(
                    "INSERT INTO $tableName (aggregate_id, job_type, fire_after, context) VALUES (?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, entry.aggregateId)
                    statement.setString(2, entry.jobType)
                    statement.setObject(3, entry.fireAfter.toUtc())
                    statement.setString(4, entry.context)
                    statement.executeUpdate()
                }
            } catch (e: SQLException) {
                if (e.sqlState == UNIQUE_VIOLATION) upsert(connection, entry) else throw e // lost the race: update
            }
        }
    }

    override fun delete(aggregateId: String, jobType: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "DELETE FROM $tableName WHERE aggregate_id = ? AND job_type = ?",
            ).use { statement ->
                statement.setString(1, aggregateId)
                statement.setString(2, jobType)
                statement.executeUpdate()
            }
        }
    }

    override fun due(now: Instant, limit: Int): List<ScheduledEntry> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT aggregate_id, job_type, fire_after, context FROM $tableName " +
                    "WHERE fire_after <= ? ORDER BY fire_after FETCH FIRST ? ROWS ONLY",
            ).use { statement ->
                statement.setObject(1, now.toUtc())
                statement.setInt(2, limit)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                ScheduledEntry(
                                    aggregateId = resultSet.getString("aggregate_id"),
                                    jobType = resultSet.getString("job_type"),
                                    fireAfter = resultSet.getObject("fire_after", LocalDateTime::class.java)
                                        .toInstant(ZoneOffset.UTC),
                                    context = resultSet.getString("context"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun Instant.toUtc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
    }
}
