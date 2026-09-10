package dev.nightjar.domainevent.spi

/**
 * Adapts the host application's transaction management — the only bridge
 * nightjar needs to it. Wrap the action passed to [run] in a new transaction:
 * commit on normal return, roll back on exception (which must propagate).
 *
 * Event processing runs through this: ALL asynchronous listeners of one event
 * plus the outbox delete execute as a single transaction — a failing listener
 * rolls the whole set back. The default is
 * [DIRECT] (no transaction); adapt Spring's `TransactionTemplate`, a JDBC
 * commit/rollback block, or whatever the host uses.
 */
public fun interface TransactionalRunner {

    public fun run(action: Runnable)

    public companion object {

        /** No transaction management: runs the action directly. */
        @JvmField
        public val DIRECT: TransactionalRunner = TransactionalRunner { it.run() }
    }
}
