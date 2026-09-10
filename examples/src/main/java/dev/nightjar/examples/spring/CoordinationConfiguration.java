package dev.nightjar.examples.spring;

import dev.nightjar.coordination.jdbc.JdbcProcessLock;
import dev.nightjar.coordination.jdbc.JdbcSchedulerStore;
import dev.nightjar.coordination.jdbc.JdbcWorkerPool;
import dev.nightjar.coordination.lock.ProcessLock;
import dev.nightjar.coordination.scheduler.SimpleScheduler;
import dev.nightjar.coordination.worker.WorkerPool;
import dev.nightjar.domainevent.jdbc.TransactionalConnectionSource;
import dev.nightjar.domainevent.spi.TransactionalRunner;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Instant;

/**
 * Spring Boot wiring for nightjar coordination — reuses the transaction beans
 * from {@link DomainEventConfiguration}, so scheduling joins Spring-managed
 * transactions and jobs run in their own.
 */
@Configuration
public class CoordinationConfiguration {

    @Bean
    ProcessLock processLock(DataSource dataSource) {
        return new JdbcProcessLock(dataSource);
    }

    @Bean(destroyMethod = "close")
    WorkerPool workerPool(DataSource dataSource) {
        return new JdbcWorkerPool(dataSource);
    }

    @Bean(destroyMethod = "close")
    SimpleScheduler simpleScheduler(
            DataSource dataSource,
            TransactionalConnectionSource connectionSource,
            ProcessLock processLock,
            WorkerPool workerPool,
            TransactionalRunner transactionalRunner) {
        return SimpleScheduler.builder()
                .store(new JdbcSchedulerStore(dataSource, connectionSource))
                .lock(processLock)
                .workerPool(workerPool) // operations can suspend the scheduler: configure("simple-scheduler", 0)
                .transactionalRunner(transactionalRunner)
                .build();
    }

    /** Register jobs, then start — after all singletons exist. */
    @Bean
    SmartInitializingSingleton schedulerStarter(SimpleScheduler scheduler) {
        return () -> {
            scheduler.register("payment-reminder", (orderId, context) -> {
                System.out.println("remind " + orderId);
                return null; // or an Instant to fire again
            });
            scheduler.start();
        };
    }

    // Inside @Transactional business code, scheduling is atomic with the change:
    //   scheduler.schedule(order.getId(), Instant.now().plus(Duration.ofDays(3)),
    //                      "payment-reminder", null);
    @SuppressWarnings("unused")
    private static Instant exampleOnly() {
        return Instant.now();
    }
}
