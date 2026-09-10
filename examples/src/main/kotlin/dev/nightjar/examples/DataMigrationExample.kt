package dev.nightjar.examples

import dev.nightjar.migrations.data.DataMigrationEngine
import dev.nightjar.migrations.data.InMemoryDataMigrationStore
import java.time.Duration

/**
 * Data migrations: reprocess entities by id, in batches, continuously.
 *
 * Parallel mode divides work across application instances (atomic DB
 * claiming); sequential mode guarantees strict global ordering. In production
 * swap [InMemoryDataMigrationStore] for `JdbcDataMigrationStore` — its tables
 * ship through the schema-migration convention.
 *
 * Composes with domain-event naturally:
 * `bus.subscribe(SchemaUpgraded::class.java) { engine.enqueue("reindex", it.event.ids) }`
 */
object DataMigrationExample {

    @JvmStatic
    fun main(args: Array<String>) {
        val engine = DataMigrationEngine.builder()
            .store(InMemoryDataMigrationStore())
            .pollInterval(Duration.ofMillis(50))
            .batchSize(2)
            .build()

        engine.register("reindex-orders") { type, ids ->
            println("[$type] reindexing batch: $ids")
        }
        engine.register("recalculate") { type, ids ->
            println("[$type] strict order: $ids")
        }

        engine.start()
        engine.use {
            // parallel: batched (size 2), claimed atomically — multi-instance safe
            it.enqueue("reindex-orders", listOf("o-1", "o-2", "o-3"))

            // sequential: strictly ordered, even across enqueue calls
            it.enqueueSequential("recalculate", listOf("acc-1", "acc-2"))
            it.enqueueSequential("recalculate", listOf("acc-3"))

            Thread.sleep(400) // demo only: let the poller run
        }
    }
}
