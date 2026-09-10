package dev.nightjar.spring

import dev.nightjar.coordination.jdbc.JdbcProcessLock
import dev.nightjar.coordination.jdbc.JdbcSchedulerStore
import dev.nightjar.coordination.jdbc.JdbcWorkerPool
import dev.nightjar.coordination.lock.ProcessLock
import dev.nightjar.coordination.scheduler.SchedulerStore
import dev.nightjar.coordination.scheduler.SimpleScheduler
import dev.nightjar.coordination.worker.WorkerPool
import dev.nightjar.domainevent.jdbc.TransactionalConnectionSource
import dev.nightjar.domainevent.spi.TransactionalRunner
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import javax.sql.DataSource

/**
 * Auto-configures the coordination primitives on the application's
 * `DataSource`: a [ProcessLock] (always), a [WorkerPool]
 * (`nightjar.coordination.worker.enabled`) and the [SimpleScheduler]
 * (`nightjar.coordination.scheduler.enabled`) with every [ScheduledJobBean]
 * auto-registered. Inject [SimpleScheduler] to schedule timers — inside
 * `@Transactional` code, scheduling is atomic with the business change.
 */
@AutoConfiguration(
    after = [NightjarTransactionAutoConfiguration::class],
    afterName = [
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    ],
)
@EnableConfigurationProperties(NightjarProperties::class)
public class NightjarCoordinationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ProcessLock::class)
    @ConditionalOnBean(DataSource::class)
    public fun nightjarProcessLock(
        dataSource: DataSource,
        connectionSource: TransactionalConnectionSource,
    ): ProcessLock = JdbcProcessLock(dataSource, connectionSource)

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(WorkerPool::class)
    @ConditionalOnBean(DataSource::class)
    @ConditionalOnProperty(prefix = "nightjar.coordination.worker", name = ["enabled"], havingValue = "true")
    public fun nightjarWorkerPool(dataSource: DataSource, properties: NightjarProperties): WorkerPool {
        val worker = properties.coordination.worker
        return JdbcWorkerPool(
            dataSource,
            permitTtl = worker.permitTtl,
            cleanupInterval = worker.cleanupInterval,
            defaultConcurrency = worker.defaultConcurrency,
        )
    }

    @Bean
    @ConditionalOnMissingBean(SchedulerStore::class)
    @ConditionalOnBean(DataSource::class)
    public fun nightjarSchedulerStore(
        dataSource: DataSource,
        connectionSource: TransactionalConnectionSource,
    ): SchedulerStore = JdbcSchedulerStore(dataSource, connectionSource)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(value = [SchedulerStore::class, ProcessLock::class])
    @ConditionalOnProperty(prefix = "nightjar.coordination.scheduler", name = ["enabled"], havingValue = "true")
    public fun nightjarSimpleScheduler(
        store: SchedulerStore,
        lock: ProcessLock,
        workerPoolProvider: ObjectProvider<WorkerPool>,
        transactionalRunnerProvider: ObjectProvider<TransactionalRunner>,
        properties: NightjarProperties,
    ): SimpleScheduler {
        val scheduler = properties.coordination.scheduler
        val builder = SimpleScheduler.builder()
            .store(store)
            .lock(lock)
            .pollInterval(scheduler.pollInterval)
            .retryDelay(scheduler.retryDelay)
            .fireLockTtl(scheduler.fireLockTtl)
            .batchSize(scheduler.batchSize)
        workerPoolProvider.ifAvailable { builder.workerPool(it) }
        transactionalRunnerProvider.ifAvailable { builder.transactionalRunner(it) }
        return builder.build()
    }

    /** Registers job beans, then starts/stops the scheduler with the context. */
    @Bean
    @ConditionalOnBean(SimpleScheduler::class)
    public fun nightjarSchedulerLifecycle(
        scheduler: SimpleScheduler,
        jobs: ObjectProvider<ScheduledJobBean>,
    ): SmartLifecycle = object : SmartLifecycle {
        @Volatile
        private var running = false

        override fun start() {
            jobs.orderedStream().forEach { job ->
                scheduler.register(job.jobType()) { aggregateId, context -> job.run(aggregateId, context) }
            }
            scheduler.start()
            running = true
        }

        override fun stop() {
            scheduler.close()
            running = false
        }

        override fun isRunning(): Boolean = running
    }
}
