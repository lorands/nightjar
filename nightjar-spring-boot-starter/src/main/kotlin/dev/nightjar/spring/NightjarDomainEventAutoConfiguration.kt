package dev.nightjar.spring

import com.fasterxml.jackson.databind.ObjectMapper
import dev.nightjar.domainevent.DomainEventListener
import dev.nightjar.domainevent.DomainEventPublisher
import dev.nightjar.domainevent.SyncDomainEventListener
import dev.nightjar.coordination.worker.WorkerPool
import dev.nightjar.domainevent.inmemory.InMemoryOutboxStore
import dev.nightjar.domainevent.inmemory.InProcessEventTransport
import dev.nightjar.domainevent.jdbc.JdbcOutboxStore
import dev.nightjar.domainevent.jdbc.TransactionalConnectionSource
import dev.nightjar.domainevent.spi.EventSerializer
import dev.nightjar.domainevent.spi.EventTransport
import dev.nightjar.domainevent.spi.MetadataProvider
import dev.nightjar.domainevent.spi.OutboxStore
import dev.nightjar.domainevent.spi.ProcessingGate
import dev.nightjar.domainevent.spi.TransactionalRunner
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/**
 * Auto-configures the domain-event stack: a JDBC outbox on the application's
 * `DataSource` (in-memory fallback without one), the configured transport
 * (in-process by default), and [NightjarDomainEvents] — the lifecycle-managed
 * bus that auto-subscribes every [DomainEventListener] /
 * [SyncDomainEventListener] bean and serves as the injectable
 * [DomainEventPublisher].
 */
@AutoConfiguration(
    after = [NightjarTransactionAutoConfiguration::class, NightjarCoordinationAutoConfiguration::class],
    afterName = [
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    ],
)
@ConditionalOnProperty(prefix = "nightjar.domain-event", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(NightjarProperties::class)
public class NightjarDomainEventAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OutboxStore::class)
    @ConditionalOnBean(DataSource::class)
    public fun nightjarJdbcOutboxStore(
        dataSource: DataSource,
        connectionSource: TransactionalConnectionSource,
    ): OutboxStore = JdbcOutboxStore(dataSource, connectionSource)

    /** Fallback for DataSource-less applications (demos, tests): heap-backed. */
    @Bean
    @ConditionalOnMissingBean(value = [OutboxStore::class, DataSource::class])
    public fun nightjarInMemoryOutboxStore(): OutboxStore = InMemoryOutboxStore()

    /** Default transport; backs off to any [EventTransport] bean (e.g. RabbitMQ). */
    @Bean
    @ConditionalOnMissingBean(EventTransport::class)
    public fun nightjarInProcessEventTransport(): EventTransport = InProcessEventTransport()

    /**
     * Cluster-wide stop driven by the worker pool: the gate is open while the
     * configured worker type is not suspended. Present only when a [WorkerPool]
     * bean exists (`nightjar.coordination.worker.enabled=true`); otherwise the
     * bus keeps [ProcessingGate.OPEN].
     */
    @Bean
    @ConditionalOnMissingBean(ProcessingGate::class)
    @ConditionalOnBean(WorkerPool::class)
    public fun nightjarProcessingGate(workerPool: WorkerPool, properties: NightjarProperties): ProcessingGate {
        val workerType = properties.domainEvent.workerType
        return ProcessingGate { !workerPool.isSuspended(workerType) }
    }

    @Bean
    @ConditionalOnMissingBean(DomainEventPublisher::class)
    public fun nightjarDomainEvents(
        store: OutboxStore,
        transport: EventTransport,
        serializerProvider: ObjectProvider<EventSerializer>,
        objectMapperProvider: ObjectProvider<ObjectMapper>,
        transactionalRunnerProvider: ObjectProvider<TransactionalRunner>,
        transactionManagerProvider: ObjectProvider<PlatformTransactionManager>,
        processingGateProvider: ObjectProvider<ProcessingGate>,
        metadataProviderProvider: ObjectProvider<MetadataProvider>,
        listeners: ObjectProvider<DomainEventListener<*>>,
        syncListeners: ObjectProvider<SyncDomainEventListener<*>>,
        properties: NightjarProperties,
        beanFactory: org.springframework.beans.factory.config.ConfigurableListableBeanFactory,
    ): NightjarDomainEvents = NightjarDomainEvents(
        store, transport, serializerProvider, objectMapperProvider,
        transactionalRunnerProvider, transactionManagerProvider, processingGateProvider,
        metadataProviderProvider, listeners, syncListeners, properties, beanFactory,
    )
}
