package dev.nightjar.spring

/**
 * A Spring bean implementing this is auto-registered with the
 * [dev.nightjar.migrations.data.DataMigrationEngine] under [type] —
 * the starter's way of declaring data-migration handlers:
 *
 * ```kotlin
 * @Component
 * class ReindexOrdersHandler(private val indexer: OrderIndexer) : DataMigrationHandlerBean {
 *     override fun type() = "reindex-orders"
 *     override fun process(ids: List<String>) = indexer.reindex(ids)
 * }
 * ```
 *
 * Batches are at-least-once: implementations must be idempotent.
 */
public interface DataMigrationHandlerBean {

    public fun type(): String

    public fun process(ids: List<String>)
}
