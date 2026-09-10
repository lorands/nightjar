package dev.nightjar.domainevent.jdbc

import java.sql.Connection

/**
 * Supplies the JDBC connection bound to the calling thread's active
 * transaction, so the outbox append commits or rolls back together with the
 * business data.
 *
 * Adapt your transaction management here — e.g. Spring:
 * `DataSourceUtils.getConnection(dataSource)` when a transaction is active.
 * For plain-JDBC applications, [JdbcTransactions] is a ready-made
 * implementation.
 *
 * Returning `null` means "no active transaction": the store then uses a
 * short-lived auto-commit connection.
 */
public fun interface TransactionalConnectionSource {

    public fun current(): Connection?

    public companion object {

        /** Never in a transaction: every append uses its own auto-commit connection. */
        @JvmField
        public val NONE: TransactionalConnectionSource = TransactionalConnectionSource { null }
    }
}
