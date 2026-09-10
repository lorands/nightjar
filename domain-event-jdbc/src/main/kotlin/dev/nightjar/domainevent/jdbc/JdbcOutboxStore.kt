package dev.nightjar.domainevent.jdbc

import dev.nightjar.domainevent.spi.OutboxRecord
import dev.nightjar.domainevent.spi.OutboxStatus
import dev.nightjar.domainevent.spi.OutboxStore
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * [OutboxStore] over plain JDBC — no external dependencies.
 *
 * Uses portable SQL (PostgreSQL and H2 verified): claiming is an atomic
 * conditional `UPDATE`, sequence locks are row locks on a dedicated lock
 * table. Timestamps are stored as UTC `TIMESTAMP`. See
 * `domain-event-postgresql.sql` on the classpath for the schema.
 *
 * [append] and [delete] join the caller's transaction through
 * [TransactionalConnectionSource] — the append commits with the publishing
 * transaction, the delete with the listeners' processing transaction. Every
 * other operation uses short autonomous connections from [dataSource].
 */
public class JdbcOutboxStore @JvmOverloads constructor(
    private val dataSource: DataSource,
    private val transactionalConnections: TransactionalConnectionSource = TransactionalConnectionSource.NONE,
    private val clock: Clock = Clock.systemUTC(),
    private val tableName: String = "domain_event",
    private val lockTableName: String = "domain_event_lock",
) : OutboxStore {

    private val columns =
        "id, event_type, payload, metadata, sequence_key, status, attempts, created_at, modified_at, suspended, last_error"

    override fun append(record: OutboxRecord) {
        val transactional = transactionalConnections.current()
        if (transactional != null) {
            insert(transactional, record) // joins the caller's transaction; not ours to commit or close
        } else {
            dataSource.connection.use { insert(it, record) }
        }
    }

    private fun insert(connection: Connection, record: OutboxRecord) {
        connection.prepareStatement(
            "INSERT INTO $tableName ($columns) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, record.id)
            statement.setString(2, record.eventType)
            statement.setBytes(3, record.payload)
            statement.setString(4, MetadataCodec.encode(record.metadata))
            statement.setString(5, record.sequenceKey)
            statement.setString(6, record.status.name)
            statement.setInt(7, record.attempts)
            statement.setObject(8, record.createdAt.toUtc())
            statement.setObject(9, record.modifiedAt?.toUtc())
            statement.setBoolean(10, record.suspended)
            statement.setString(11, record.lastError)
            statement.executeUpdate()
        }
    }

    override fun findPending(limit: Int): List<OutboxRecord> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT $columns FROM $tableName " +
                    "WHERE suspended = FALSE AND status <> 'PROCESSING' " +
                    "ORDER BY created_at FETCH FIRST ? ROWS ONLY",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.toRecord())
                    }
                }
            }
        }

    override fun markSent(id: String) {
        executeUpdate("UPDATE $tableName SET status = 'SENT', modified_at = ? WHERE id = ?") {
            it.setObject(1, now())
            it.setString(2, id)
        }
    }

    override fun claim(id: String): OutboxRecord? =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                // Atomic claim: the conditional UPDATE succeeds for exactly one
                // competing consumer; everyone else sees updateCount == 0.
                val claimed = connection.prepareStatement(
                    "UPDATE $tableName SET status = 'PROCESSING', modified_at = ? " +
                        "WHERE id = ? AND status <> 'PROCESSING' AND suspended = FALSE",
                ).use { statement ->
                    statement.setObject(1, now())
                    statement.setString(2, id)
                    statement.executeUpdate() == 1
                }
                val record = if (claimed) selectById(connection, id) else null
                connection.commit()
                record
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    override fun delete(id: String) {
        val transactional = transactionalConnections.current()
        if (transactional != null) {
            // joins the processing transaction: the record disappears
            // atomically with the listeners' committed work
            transactional.prepareStatement("DELETE FROM $tableName WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
        } else {
            executeUpdate("DELETE FROM $tableName WHERE id = ?") { it.setString(1, id) }
        }
    }

    override fun markError(id: String, error: String) {
        executeUpdate(
            "UPDATE $tableName SET status = 'ERROR', attempts = attempts + 1, modified_at = ?, last_error = ? WHERE id = ?",
        ) {
            it.setObject(1, now())
            it.setString(2, error)
            it.setString(3, id)
        }
    }

    override fun markSuspended(id: String) {
        executeUpdate("UPDATE $tableName SET suspended = TRUE, modified_at = ? WHERE id = ?") {
            it.setObject(1, now())
            it.setString(2, id)
        }
    }

    override fun resume(id: String) {
        executeUpdate(
            "UPDATE $tableName SET status = 'CREATED', suspended = FALSE, modified_at = ? WHERE id = ?",
        ) {
            it.setObject(1, now())
            it.setString(2, id)
        }
    }

    override fun withSequenceLock(key: String, action: Runnable) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                ensureLockRow(connection, key)
                // Blocks until any other holder of this key commits — a portable,
                // cross-instance mutex held for the duration of the transaction.
                connection.prepareStatement("SELECT lock_key FROM $lockTableName WHERE lock_key = ? FOR UPDATE")
                    .use { statement ->
                        statement.setString(1, key)
                        statement.executeQuery().use { it.next() }
                    }
                action.run()
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    private fun ensureLockRow(connection: Connection, key: String) {
        val exists = connection.prepareStatement("SELECT lock_key FROM $lockTableName WHERE lock_key = ?")
            .use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { it.next() }
            }
        if (!exists) {
            try {
                connection.prepareStatement("INSERT INTO $lockTableName (lock_key) VALUES (?)").use { statement ->
                    statement.setString(1, key)
                    statement.executeUpdate()
                }
                connection.commit() // make the row visible to other instances immediately
            } catch (e: SQLException) {
                if (e.sqlState == UNIQUE_VIOLATION) connection.rollback() else throw e // concurrent insert won
            }
        }
    }

    private fun executeUpdate(sql: String, bind: (PreparedStatement) -> Unit) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeUpdate()
            }
        }
    }

    private fun selectById(connection: Connection, id: String): OutboxRecord? =
        connection.prepareStatement("SELECT $columns FROM $tableName WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toRecord() else null
            }
        }

    private fun ResultSet.toRecord(): OutboxRecord = OutboxRecord(
        id = getString("id"),
        eventType = getString("event_type"),
        payload = getBytes("payload"),
        metadata = MetadataCodec.decode(getString("metadata")),
        sequenceKey = getString("sequence_key"),
        status = OutboxStatus.valueOf(getString("status")),
        attempts = getInt("attempts"),
        createdAt = getObject("created_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
        modifiedAt = getObject("modified_at", LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC),
        suspended = getBoolean("suspended"),
        lastError = getString("last_error"),
    )

    private fun now(): LocalDateTime = clock.instant().toUtc()

    private fun Instant.toUtc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
    }
}
