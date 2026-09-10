package dev.nightjar.migrations.jdbc

import dev.nightjar.domainevent.jdbc.TransactionalConnectionSource
import dev.nightjar.migrations.data.ClaimedBatch
import dev.nightjar.migrations.data.DataMigrationStore
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * [DataMigrationStore] over plain JDBC — no external dependencies, portable
 * SQL (PostgreSQL and H2 verified). Schema ships at
 * `dev/nightjar/migrations/jdbc/data-migration-postgresql.sql` and is
 * contributed to hosts via the nightjar migrations manifest.
 *
 * [enqueue] joins the caller's transaction through
 * [TransactionalConnectionSource]; all other operations use short autonomous
 * connections. Claiming is optimistic: a conditional per-row `UPDATE` decides
 * winners, so any number of instances can poll concurrently.
 */
public class JdbcDataMigrationStore @JvmOverloads constructor(
    private val dataSource: DataSource,
    private val transactionalConnections: TransactionalConnectionSource = TransactionalConnectionSource.NONE,
    private val clock: Clock = Clock.systemUTC(),
    private val tableName: String = "data_migration",
    private val sequentialTableName: String = "sequential_data_migration",
    private val lockTableName: String = "data_migration_lock",
) : DataMigrationStore {

    override fun enqueue(type: String, ids: Collection<String>, priority: Int) {
        val transactional = transactionalConnections.current()
        if (transactional != null) {
            insertAll(transactional, type, ids, priority)
        } else {
            dataSource.connection.use { insertAll(it, type, ids, priority) }
        }
    }

    private fun insertAll(connection: Connection, type: String, ids: Collection<String>, priority: Int) {
        connection.prepareStatement("INSERT INTO $tableName (id, type, priority) VALUES (?, ?, ?)").use { statement ->
            for (id in ids) {
                statement.setString(1, id)
                statement.setString(2, type)
                statement.setInt(3, priority)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    override fun claimNextBatch(
        claimToken: String,
        batchSize: Int,
        claimExpiry: Duration,
        excludedTypes: Collection<String>,
    ): ClaimedBatch? = dataSource.connection.use { connection ->
        val now = clock.instant()
        val expiryCutoff = (now - claimExpiry).toUtc()
        val exclusion = if (excludedTypes.isEmpty()) "" else
            "AND type NOT IN (${excludedTypes.joinToString(",") { "?" }}) "

        // 1. pick the highest-priority claimable type
        val type = connection.prepareStatement(
            "SELECT type FROM $tableName " +
                "WHERE (claimed_by IS NULL OR claimed_at < ?) $exclusion" +
                "ORDER BY priority, type FETCH FIRST 1 ROWS ONLY",
        ).use { statement ->
            statement.setObject(1, expiryCutoff)
            excludedTypes.forEachIndexed { index, excluded -> statement.setString(index + 2, excluded) }
            statement.executeQuery().use { if (it.next()) it.getString(1) else null }
        } ?: return null

        // 2. candidate rows of that type
        val candidates = connection.prepareStatement(
            "SELECT id FROM $tableName " +
                "WHERE type = ? AND (claimed_by IS NULL OR claimed_at < ?) " +
                "ORDER BY priority FETCH FIRST ? ROWS ONLY",
        ).use { statement ->
            statement.setString(1, type)
            statement.setObject(2, expiryCutoff)
            statement.setInt(3, batchSize)
            statement.executeQuery().use { resultSet ->
                buildList { while (resultSet.next()) add(resultSet.getString(1)) }
            }
        }
        if (candidates.isEmpty()) return null

        // 3. claim optimistically — competing instances split the candidates
        val claimed = connection.prepareStatement(
            "UPDATE $tableName SET claimed_by = ?, claimed_at = ? " +
                "WHERE id = ? AND type = ? AND (claimed_by IS NULL OR claimed_at < ?)",
        ).use { statement ->
            candidates.filter { id ->
                statement.setString(1, claimToken)
                statement.setObject(2, now.toUtc())
                statement.setString(3, id)
                statement.setString(4, type)
                statement.setObject(5, expiryCutoff)
                statement.executeUpdate() > 0
            }
        }
        if (claimed.isEmpty()) null else ClaimedBatch(claimToken, type, claimed)
    }

    override fun deleteClaimed(batch: ClaimedBatch) {
        executeUpdate("DELETE FROM $tableName WHERE claimed_by = ?") { it.setString(1, batch.claimToken) }
    }

    override fun releaseClaim(batch: ClaimedBatch, countFailure: Boolean) {
        val attempts = if (countFailure) ", attempts = attempts + 1" else ""
        executeUpdate("UPDATE $tableName SET claimed_by = NULL, claimed_at = NULL$attempts WHERE claimed_by = ?") {
            it.setString(1, batch.claimToken)
        }
    }

    override fun enqueueSequential(type: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        // Global insertion order is the contract: serialize enqueuers on a lock
        // row, then append with strictly increasing row_index.
        withLockRow(ENQUEUE_LOCK_KEY) { connection ->
            val nextIndex = connection.prepareStatement(
                "SELECT COALESCE(MAX(row_index), 0) + 1 FROM $sequentialTableName",
            ).use { statement ->
                statement.executeQuery().use { it.next(); it.getLong(1) }
            }
            connection.prepareStatement(
                "INSERT INTO $sequentialTableName (id, type, row_index) VALUES (?, ?, ?)",
            ).use { statement ->
                ids.forEachIndexed { offset, id ->
                    statement.setString(1, id)
                    statement.setString(2, type)
                    statement.setLong(3, nextIndex + offset)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    override fun peekNextSequentialType(): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT type FROM $sequentialTableName ORDER BY row_index FETCH FIRST 1 ROWS ONLY",
            ).use { statement ->
                statement.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
        }

    override fun nextSequentialBatch(type: String, batchSize: Int): List<String> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT id, type FROM $sequentialTableName ORDER BY row_index FETCH FIRST ? ROWS ONLY",
            ).use { statement ->
                statement.setInt(1, batchSize)
                statement.executeQuery().use { resultSet ->
                    // strict global order: only the contiguous head run of this type
                    buildList {
                        while (resultSet.next()) {
                            if (resultSet.getString("type") != type) break
                            add(resultSet.getString("id"))
                        }
                    }
                }
            }
        }

    override fun deleteSequential(type: String, ids: Collection<String>) {
        // head-run semantics: the first ids.size rows of this type by row_index
        // (new rows always append with larger indexes, so this matches exactly
        // the rows handed out by nextSequentialBatch)
        executeUpdate(
            "DELETE FROM $sequentialTableName WHERE row_index IN (" +
                "SELECT row_index FROM $sequentialTableName WHERE type = ? " +
                "ORDER BY row_index FETCH FIRST ? ROWS ONLY)",
        ) {
            it.setString(1, type)
            it.setInt(2, ids.size)
        }
    }

    override fun withTypeLock(type: String, action: Runnable) {
        withLockRow("type:$type") { action.run() }
    }

    // ------------------------------------------------------------------ plumbing

    private fun withLockRow(key: String, action: (Connection) -> Unit) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                ensureLockRow(connection, key)
                connection.prepareStatement("SELECT lock_key FROM $lockTableName WHERE lock_key = ? FOR UPDATE")
                    .use { statement ->
                        statement.setString(1, key)
                        statement.executeQuery().use { it.next() }
                    }
                action(connection)
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
                connection.commit()
            } catch (e: SQLException) {
                if (e.sqlState == UNIQUE_VIOLATION) connection.rollback() else throw e
            }
        }
    }

    private fun executeUpdate(sql: String, bind: (java.sql.PreparedStatement) -> Unit) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeUpdate()
            }
        }
    }

    private fun Instant.toUtc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        const val ENQUEUE_LOCK_KEY = "sequential-enqueue"
    }
}
