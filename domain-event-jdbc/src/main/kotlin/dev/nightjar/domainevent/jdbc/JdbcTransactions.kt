package dev.nightjar.domainevent.jdbc

import dev.nightjar.domainevent.spi.TransactionalRunner
import java.sql.Connection
import javax.sql.DataSource

/**
 * Minimal thread-bound transaction management for applications without a
 * framework: one connection per [run] block, committed on success, rolled
 * back on exception.
 *
 * Wire the same instance as the bus's
 * [dev.nightjar.domainevent.spi.TransactionalRunner] and as the
 * [JdbcOutboxStore]'s [TransactionalConnectionSource] — then listener work,
 * business data and outbox appends share one transaction. Application code
 * inside a block reaches the transaction via [current].
 */
public class JdbcTransactions(
    private val dataSource: DataSource,
) : TransactionalRunner, TransactionalConnectionSource {

    private val active = ThreadLocal<Connection?>()

    override fun run(action: Runnable) {
        check(active.get() == null) { "Nested transactions are not supported" }
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            active.set(connection)
            try {
                action.run()
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                active.remove()
            }
        }
    }

    /** The connection of the calling thread's active transaction, or `null`. */
    override fun current(): Connection? = active.get()
}
