package dev.nightjar.coordination.jdbc

import dev.nightjar.coordination.worker.WorkerPool
import java.lang.System.Logger.Level
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * [WorkerPool] over plain JDBC — worker rows are held permits; the per-type
 * `worker_config` row (locked `FOR UPDATE` during acquisition) serializes
 * competing acquisitions cluster-wide. Permits older than [permitTtl] count
 * as abandoned: ignored when counting and removed by a background sweep every
 * [cleanupInterval] (defaults: 10 min / 10 s).
 */
public class JdbcWorkerPool @JvmOverloads constructor(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
    private val permitTtl: Duration = Duration.ofMinutes(10),
    cleanupInterval: Duration = Duration.ofSeconds(10),
    private val defaultConcurrency: Int = 1,
    private val workerTableName: String = "worker",
    private val configTableName: String = "worker_config",
) : WorkerPool {

    private val log = System.getLogger(JdbcWorkerPool::class.java.name)
    private val cleaner = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nightjar-worker-cleanup").apply { isDaemon = true }
    }

    init {
        cleaner.scheduleWithFixedDelay(
            ::sweepExpired, cleanupInterval.toMillis(), cleanupInterval.toMillis(), TimeUnit.MILLISECONDS,
        )
    }

    override fun newWorker(type: String): String? =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                ensureConfigRow(connection, type)
                // serialize competing acquisitions on the config row
                val concurrency = connection.prepareStatement(
                    "SELECT concurrency FROM $configTableName WHERE type = ? FOR UPDATE",
                ).use { statement ->
                    statement.setString(1, type)
                    statement.executeQuery().use { it.next(); it.getInt(1) }
                }

                val uuid = if (concurrency > 0 && liveWorkers(connection, type) < concurrency) {
                    val newUuid = UUID.randomUUID().toString()
                    connection.prepareStatement(
                        "INSERT INTO $workerTableName (uuid, type, created) VALUES (?, ?, ?)",
                    ).use { statement ->
                        statement.setString(1, newUuid)
                        statement.setString(2, type)
                        statement.setObject(3, clock.instant().toUtc())
                        statement.executeUpdate()
                    }
                    newUuid
                } else {
                    null
                }
                connection.commit()
                uuid
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    private fun liveWorkers(connection: Connection, type: String): Int =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM $workerTableName WHERE type = ? AND created > ?",
        ).use { statement ->
            statement.setString(1, type)
            statement.setObject(2, clock.instant().minus(permitTtl).toUtc())
            statement.executeQuery().use { it.next(); it.getInt(1) }
        }

    override fun terminate(uuid: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM $workerTableName WHERE uuid = ?").use { statement ->
                statement.setString(1, uuid)
                statement.executeUpdate()
            }
        }
    }

    override fun configure(type: String, concurrency: Int) {
        require(concurrency >= 0) { "concurrency must be >= 0" }
        dataSource.connection.use { connection ->
            val updated = connection.prepareStatement(
                "UPDATE $configTableName SET concurrency = ? WHERE type = ?",
            ).use { statement ->
                statement.setInt(1, concurrency)
                statement.setString(2, type)
                statement.executeUpdate()
            }
            if (updated == 0) {
                try {
                    connection.prepareStatement(
                        "INSERT INTO $configTableName (type, concurrency) VALUES (?, ?)",
                    ).use { statement ->
                        statement.setString(1, type)
                        statement.setInt(2, concurrency)
                        statement.executeUpdate()
                    }
                } catch (e: SQLException) {
                    if (e.sqlState != UNIQUE_VIOLATION) throw e // concurrent insert: retry update
                    configure(type, concurrency)
                }
            }
        }
    }

    override fun isSuspended(type: String): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT concurrency FROM $configTableName WHERE type = ?").use { statement ->
                statement.setString(1, type)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getInt(1) == 0 else defaultConcurrency == 0
                }
            }
        }

    private fun ensureConfigRow(connection: Connection, type: String) {
        val exists = connection.prepareStatement("SELECT 1 FROM $configTableName WHERE type = ?").use { statement ->
            statement.setString(1, type)
            statement.executeQuery().use { it.next() }
        }
        if (!exists) {
            try {
                connection.prepareStatement(
                    "INSERT INTO $configTableName (type, concurrency) VALUES (?, ?)",
                ).use { statement ->
                    statement.setString(1, type)
                    statement.setInt(2, defaultConcurrency)
                    statement.executeUpdate()
                }
                connection.commit() // visible to other instances immediately
            } catch (e: SQLException) {
                if (e.sqlState == UNIQUE_VIOLATION) connection.rollback() else throw e
            }
        }
    }

    private fun sweepExpired() {
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM $workerTableName WHERE created < ?").use { statement ->
                    statement.setObject(1, clock.instant().minus(permitTtl).toUtc())
                    statement.executeUpdate()
                }
            }
        } catch (e: Exception) {
            log.log(Level.WARNING, "Expired-worker sweep failed", e)
        }
    }

    override fun close() {
        cleaner.shutdown()
        if (!cleaner.awaitTermination(5, TimeUnit.SECONDS)) {
            cleaner.shutdownNow()
        }
    }

    private fun Instant.toUtc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
    }
}
