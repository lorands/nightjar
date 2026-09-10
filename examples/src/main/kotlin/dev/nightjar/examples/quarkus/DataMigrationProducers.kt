package dev.nightjar.examples.quarkus

import dev.nightjar.domainevent.jdbc.TransactionalConnectionSource
import dev.nightjar.domainevent.spi.TransactionalRunner
import dev.nightjar.migrations.data.DataMigrationEngine
import dev.nightjar.migrations.jdbc.JdbcDataMigrationStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Initialized
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Disposes
import jakarta.enterprise.inject.Produces
import jakarta.transaction.Status
import jakarta.transaction.UserTransaction
import javax.sql.DataSource

/**
 * Quarkus wiring for nightjar data migrations — pure CDI + JTA, mirroring
 * [DomainEventProducers].
 *
 * Schema migrations are a DEPLOY-time concern: run
 * `dev.nightjar.migrations.liquibase.Main` in an init container (recommended),
 * or apply on startup from the `onStart` observer if you accept
 * migrate-on-startup semantics.
 */
@ApplicationScoped
class DataMigrationProducers {

    @Produces
    @ApplicationScoped
    fun dataMigrationEngine(dataSource: DataSource, userTransaction: UserTransaction): DataMigrationEngine {
        val connectionSource = TransactionalConnectionSource {
            if (userTransaction.status == Status.STATUS_ACTIVE) dataSource.connection else null
        }
        val transactionalRunner = TransactionalRunner { action ->
            userTransaction.begin()
            try {
                action.run()
                userTransaction.commit()
            } catch (e: Exception) {
                userTransaction.rollback()
                throw e
            }
        }
        return DataMigrationEngine.builder()
            .store(JdbcDataMigrationStore(dataSource, connectionSource))
            .transactionalRunner(transactionalRunner)
            .build()
    }

    /** Register handlers, then start — once, on application startup. */
    fun onStart(@Observes @Initialized(ApplicationScoped::class) ignored: Any, engine: DataMigrationEngine) {
        engine.register("reindex-orders") { _, ids ->
            println("reindexing ${ids.size} orders")
        }
        engine.start()
    }

    fun close(@Disposes engine: DataMigrationEngine) {
        engine.close()
    }
}
