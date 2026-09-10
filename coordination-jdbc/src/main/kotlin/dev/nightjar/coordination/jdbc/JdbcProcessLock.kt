package dev.nightjar.coordination.jdbc

import dev.nightjar.coordination.lock.ProcessLock
import dev.nightjar.domainevent.jdbc.TransactionalConnectionSource
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * [ProcessLock] over plain JDBC — existence-based: a row in [tableName] is a
 * held lock. Acquisition is an `INSERT` (losers hit the primary key); expired
 * leases are taken over with one atomic conditional `UPDATE`. Portable SQL
 * (PostgreSQL and H2 verified).
 *
 * When a [TransactionalConnectionSource] is supplied and a transaction is
 * active, `tryAcquire`/`release`/`exists` run on the caller's connection — so
 * **the lock is bound to that transaction**: a rollback releases it, a commit
 * holds it. The contended
 * `INSERT` is guarded by a savepoint, so a "lock already held" result (`false`)
 * never aborts the caller's transaction. Without an active transaction (or
 * without a source), every operation uses a short autonomous connection and
 * the lease TTL is the only crash-recovery net.
 */
public class JdbcProcessLock @JvmOverloads constructor(
    private val dataSource: DataSource,
    private val transactionalConnections: TransactionalConnectionSource = TransactionalConnectionSource.NONE,
    private val clock: Clock = Clock.systemUTC(),
    private val tableName: String = "process_lock",
    /** Diagnostic holder id written to the `owner` column. */
    private val ownerId: String = "nightjar-" + UUID.randomUUID().toString().take(8),
) : ProcessLock {

    override fun tryAcquire(id: String, ttl: Duration?): Boolean {
        val now = clock.instant()
        val expiresAt = ttl?.let { now.plus(it) }
        val transactional = transactionalConnections.current()
        return if (transactional != null) {
            acquireJoiningTransaction(transactional, id, now, expiresAt)
        } else {
            dataSource.connection.use { insert(it, id, now, expiresAt) } ||
                dataSource.connection.use { takeOverExpired(it, id, now, expiresAt) }
        }
    }

    /**
     * Acquire on the caller's transaction. The `INSERT` sits behind a savepoint
     * so a unique violation (lock held) rolls back only that statement, leaving
     * the caller's transaction usable, then we attempt lease takeover.
     */
    private fun acquireJoiningTransaction(connection: Connection, id: String, now: Instant, expiresAt: Instant?): Boolean {
        val savepoint = connection.setSavepoint("nightjar_lock")
        return try {
            insertOrThrow(connection, id, now, expiresAt)
            connection.releaseSavepoint(savepoint)
            true
        } catch (e: SQLException) {
            if (e.sqlState != UNIQUE_VIOLATION) throw e
            connection.rollback(savepoint) // undo the failed INSERT; keep the transaction alive
            takeOverExpired(connection, id, now, expiresAt)
        }
    }

    private fun insert(connection: Connection, id: String, now: Instant, expiresAt: Instant?): Boolean =
        try {
            insertOrThrow(connection, id, now, expiresAt)
            true
        } catch (e: SQLException) {
            if (e.sqlState == UNIQUE_VIOLATION) false else throw e
        }

    private fun insertOrThrow(connection: Connection, id: String, now: Instant, expiresAt: Instant?) {
        connection.prepareStatement(
            "INSERT INTO $tableName (id, owner, acquired_at, expires_at) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, ownerId)
            statement.setObject(3, now.toUtc())
            statement.setObject(4, expiresAt?.toUtc())
            statement.executeUpdate()
        }
    }

    private fun takeOverExpired(connection: Connection, id: String, now: Instant, expiresAt: Instant?): Boolean =
        connection.prepareStatement(
            "UPDATE $tableName SET owner = ?, acquired_at = ?, expires_at = ? " +
                "WHERE id = ? AND expires_at IS NOT NULL AND expires_at < ?",
        ).use { statement ->
            statement.setString(1, ownerId)
            statement.setObject(2, now.toUtc())
            statement.setObject(3, expiresAt?.toUtc())
            statement.setString(4, id)
            statement.setObject(5, now.toUtc())
            statement.executeUpdate() == 1
        }

    override fun exists(id: String): Boolean = onConnection { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM $tableName WHERE id = ? AND (expires_at IS NULL OR expires_at > ?)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setObject(2, clock.instant().toUtc())
            statement.executeQuery().use { it.next() }
        }
    }

    override fun release(id: String) {
        onConnection { connection ->
            connection.prepareStatement("DELETE FROM $tableName WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
        }
    }

    /** Run [block] on the active transaction's connection, or a short autonomous one. */
    private inline fun <T> onConnection(block: (Connection) -> T): T {
        val transactional = transactionalConnections.current()
        return if (transactional != null) block(transactional) else dataSource.connection.use(block)
    }

    private fun Instant.toUtc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
    }
}
