package dev.nightjar.examples.spring;

import com.rabbitmq.client.ConnectionFactory;
import dev.nightjar.coordination.jdbc.JdbcWorkerPool;
import dev.nightjar.coordination.worker.WorkerPool;
import dev.nightjar.domainevent.DomainEventBus;
import dev.nightjar.domainevent.jdbc.JdbcOutboxStore;
import dev.nightjar.domainevent.jdbc.TransactionalConnectionSource;
import dev.nightjar.domainevent.rabbitmq.RabbitMqEventTransport;
import dev.nightjar.domainevent.spi.TransactionalRunner;
import dev.nightjar.examples.JavaQuickstart;
import dev.nightjar.examples.JavaQuickstart.OrderShipped;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Spring Boot wiring for nightjar domain-event. Plain Spring Framework APIs —
 * drop this {@code @Configuration} into any Boot 3/4 application.
 *
 * <p>Gradle dependencies of the host application:</p>
 * <pre>
 * implementation("com.lorands.nightjar:domain-event-jdbc:<version>")
 * implementation("com.lorands.nightjar:domain-event-rabbitmq:<version>")
 * </pre>
 *
 * <p>The nightjar libraries themselves have no Spring dependency — this class
 * is the only place where the two worlds meet.</p>
 */
@Configuration
public class DomainEventConfiguration {

    /**
     * Joins outbox appends to the Spring-managed transaction: publishing inside
     * {@code @Transactional} code commits or rolls back atomically with the
     * business data.
     */
    @Bean
    TransactionalConnectionSource transactionalConnectionSource(DataSource dataSource) {
        return () -> TransactionSynchronizationManager.isActualTransactionActive()
                ? DataSourceUtils.getConnection(dataSource)
                : null;
    }

    @Bean
    JdbcOutboxStore outboxStore(DataSource dataSource, TransactionalConnectionSource connectionSource) {
        return new JdbcOutboxStore(dataSource, connectionSource);
    }

    /** One Spring transaction per event delivery: all async listeners + the outbox delete. */
    @Bean
    TransactionalRunner transactionalRunner(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return action -> template.executeWithoutResult(status -> action.run());
    }

    /**
     * Distributed throttle that doubles as the cluster-wide stop switch:
     * {@code workerPool.configure("myapp-domain-events", 0)} pauses event
     * processing on every instance, {@code 1} resumes it.
     */
    @Bean(destroyMethod = "close")
    WorkerPool workerPool(DataSource dataSource) {
        return new JdbcWorkerPool(dataSource);
    }

    @Bean(destroyMethod = "close")
    DomainEventBus domainEventBus(
            JdbcOutboxStore store, TransactionalRunner transactionalRunner, WorkerPool workerPool) {
        ConnectionFactory rabbit = new ConnectionFactory();
        rabbit.setHost("rabbitmq"); // bind from configuration properties in a real application

        return DomainEventBus.builder()
                .serializer(JavaQuickstart.SERIALIZER)
                .store(store)
                .transport(new RabbitMqEventTransport(rabbit, "myapp.domain.events"))
                .transactionalRunner(transactionalRunner)
                // open while the worker type is not suspended — a DB-backed
                // switch every instance sees
                .processingGate(() -> !workerPool.isSuspended("myapp-domain-events"))
                .build();
    }

    /**
     * Subscribe listeners, then start — after all singletons exist, so no
     * message can arrive before its listener is registered.
     */
    @Bean
    SmartInitializingSingleton domainEventBusStarter(DomainEventBus bus) {
        return () -> {
            bus.subscribe(OrderShipped.class, envelope ->
                    System.out.println("shipped: " + envelope.getEvent().trackingCode()));
            bus.start();
        };
    }
}
