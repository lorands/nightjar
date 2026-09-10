package dev.nightjar.spring

import dev.nightjar.domainevent.jdbc.TransactionalConnectionSource
import dev.nightjar.domainevent.spi.TransactionalRunner
import dev.nightjar.migrations.data.DataMigrationEngine
import dev.nightjar.migrations.data.DataMigrationStore
import dev.nightjar.migrations.jdbc.JdbcDataMigrationStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import javax.sql.DataSource

/**
 * Auto-configures the migrations stack:
 *
 * - `nightjar.migrations.apply-on-startup=true` (+ `migrations-liquibase` on
 *   the classpath) applies all discovered schema migrations during context
 *   refresh — before any nightjar engine starts. An init container remains
 *   the recommended production shape.
 * - `nightjar.migrations.data.enabled=true` starts the
 *   [DataMigrationEngine]; every [DataMigrationHandlerBean] is registered
 *   automatically. Inject the engine to enqueue work.
 */
@AutoConfiguration(
    after = [NightjarTransactionAutoConfiguration::class],
    afterName = [
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    ],
)
@EnableConfigurationProperties(NightjarProperties::class)
public class NightjarMigrationsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DataMigrationStore::class)
    @ConditionalOnBean(DataSource::class)
    public fun nightjarDataMigrationStore(
        dataSource: DataSource,
        connectionSource: TransactionalConnectionSource,
    ): DataMigrationStore = JdbcDataMigrationStore(dataSource, connectionSource)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataMigrationStore::class)
    @ConditionalOnProperty(prefix = "nightjar.migrations.data", name = ["enabled"], havingValue = "true")
    public fun nightjarDataMigrationEngine(
        store: DataMigrationStore,
        transactionalRunnerProvider: ObjectProvider<TransactionalRunner>,
        properties: NightjarProperties,
    ): DataMigrationEngine {
        val data = properties.migrations.data
        val builder = DataMigrationEngine.builder()
            .store(store)
            .pollInterval(data.pollInterval)
            .batchSize(data.batchSize)
            .sequentialBatchSize(data.sequentialBatchSize)
            .claimExpiry(data.claimExpiry)
        transactionalRunnerProvider.ifAvailable { builder.transactionalRunner(it) }
        return builder.build()
    }

    /** Registers handler beans, then starts/stops the engine with the context. */
    @Bean
    @ConditionalOnBean(DataMigrationEngine::class)
    public fun nightjarDataMigrationLifecycle(
        engine: DataMigrationEngine,
        handlers: ObjectProvider<DataMigrationHandlerBean>,
    ): SmartLifecycle = object : SmartLifecycle {
        @Volatile
        private var running = false

        override fun start() {
            handlers.orderedStream().forEach { handler ->
                engine.register(handler.type()) { _, ids -> handler.process(ids) }
            }
            engine.start()
            running = true
        }

        override fun stop() {
            engine.close()
            running = false
        }

        override fun isRunning(): Boolean = running
    }

    /**
     * Schema migrations during startup — opt-in; requires
     * `dev.nightjar:migrations-liquibase` on the classpath.
     */
    @AutoConfiguration
    @ConditionalOnClass(name = ["dev.nightjar.migrations.liquibase.LiquibaseMigrator"])
    @ConditionalOnProperty(prefix = "nightjar.migrations", name = ["apply-on-startup"], havingValue = "true")
    public class SchemaMigrationsOnStartup {

        @Bean
        @ConditionalOnBean(DataSource::class)
        public fun nightjarSchemaMigrations(dataSource: DataSource): Any {
            // runs during bean creation, i.e. before SmartLifecycle starts any engine
            dataSource.connection.use { connection ->
                dev.nightjar.migrations.liquibase.LiquibaseMigrator().migrate(connection)
            }
            return Object()
        }
    }
}
