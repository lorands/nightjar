package dev.nightjar.examples.spring;

import dev.nightjar.domainevent.jdbc.TransactionalConnectionSource;
import dev.nightjar.domainevent.spi.TransactionalRunner;
import dev.nightjar.migrations.data.DataMigrationEngine;
import dev.nightjar.migrations.jdbc.JdbcDataMigrationStore;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Spring Boot wiring for nightjar data migrations — reuses the
 * {@code TransactionalConnectionSource} and {@code TransactionalRunner} beans
 * from {@link DomainEventConfiguration}: one transaction story for outbox and
 * data migrations alike.
 *
 * <p>Schema migrations are a DEPLOY-time concern: run
 * {@code dev.nightjar.migrations.liquibase.Main} in an init container, or call
 * {@code new LiquibaseMigrator().migrate(...)} from a CommandLineRunner if you
 * accept migrate-on-startup semantics.</p>
 */
@Configuration
public class DataMigrationConfiguration {

    @Bean
    JdbcDataMigrationStore dataMigrationStore(DataSource dataSource, TransactionalConnectionSource connectionSource) {
        return new JdbcDataMigrationStore(dataSource, connectionSource);
    }

    @Bean(destroyMethod = "close")
    DataMigrationEngine dataMigrationEngine(JdbcDataMigrationStore store, TransactionalRunner transactionalRunner) {
        return DataMigrationEngine.builder()
                .store(store)
                .transactionalRunner(transactionalRunner)
                .build();
    }

    /** Register handlers, then start — after all singletons exist. */
    @Bean
    SmartInitializingSingleton dataMigrationStarter(DataMigrationEngine engine) {
        return () -> {
            engine.register("reindex-orders", (type, ids) ->
                    System.out.println("reindexing " + ids.size() + " orders"));
            engine.start();
        };
    }
}
