package dev.nightjar.examples.quarkus

import dev.nightjar.coordination.jdbc.JdbcWorkerPool
import dev.nightjar.coordination.worker.WorkerPool
import dev.nightjar.domainevent.DomainEventBus
import dev.nightjar.domainevent.jdbc.JdbcOutboxStore
import dev.nightjar.domainevent.jdbc.TransactionalConnectionSource
import dev.nightjar.domainevent.spi.TransactionalRunner
import dev.nightjar.domainevent.inmemory.InProcessEventTransport
import dev.nightjar.examples.ExampleSerializer
import dev.nightjar.examples.OrderPlaced
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Initialized
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Disposes
import jakarta.enterprise.inject.Produces
import jakarta.transaction.Status
import jakarta.transaction.UserTransaction
import javax.sql.DataSource

/**
 * Quarkus wiring for nightjar domain-event — pure CDI + JTA, no
 * Quarkus-specific APIs (works on any Jakarta EE 10 runtime).
 *
 * Host application dependencies: `quarkus-agroal` + your JDBC driver provide
 * the injected [DataSource]; Narayana (built into Quarkus) provides the
 * [UserTransaction].
 *
 * The nightjar libraries themselves have no CDI/Quarkus dependency — this
 * class is the only place where the two worlds meet.
 */
@ApplicationScoped
class DomainEventProducers {

    /**
     * Distributed throttle doubling as the cluster-wide stop switch:
     * `configure("myapp-domain-events", 0)` pauses processing on every
     * instance, `1` resumes it.
     */
    @Produces
    @ApplicationScoped
    fun workerPool(dataSource: DataSource): WorkerPool = JdbcWorkerPool(dataSource)

    fun closeWorkerPool(@Disposes pool: WorkerPool) {
        pool.close()
    }

    @Produces
    @ApplicationScoped
    fun domainEventBus(
        dataSource: DataSource,
        userTransaction: UserTransaction,
        workerPool: WorkerPool,
    ): DomainEventBus {
        // During an active JTA transaction Agroal hands out connections enlisted
        // in it, released back to the pool when the transaction completes — so
        // outbox appends commit/roll back with the business data.
        val connectionSource = TransactionalConnectionSource {
            if (userTransaction.status == Status.STATUS_ACTIVE) dataSource.connection else null
        }

        // One JTA transaction per event delivery: all async listeners + the outbox delete.
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

        return DomainEventBus.builder()
            .serializer(ExampleSerializer)
            .store(JdbcOutboxStore(dataSource, connectionSource))
            .transport(InProcessEventTransport()) // production: RabbitMqEventTransport(factory, "myapp.domain.events")
            .transactionalRunner(transactionalRunner)
            // open while the worker type is not suspended — a DB-backed switch every instance sees
            .processingGate { !workerPool.isSuspended("myapp-domain-events") }
            .build()
    }

    /** Subscribe listeners, then start — once, on application startup. */
    fun onStart(@Observes @Initialized(ApplicationScoped::class) ignored: Any, bus: DomainEventBus) {
        bus.subscribe(OrderPlaced::class.java) { envelope ->
            println("placed: ${envelope.event.orderId}")
        }
        bus.start()
    }

    fun close(@Disposes bus: DomainEventBus) {
        bus.close()
    }
}
