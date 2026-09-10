package dev.nightjar.examples.quarkus

import dev.nightjar.coordination.jdbc.JdbcProcessLock
import dev.nightjar.coordination.jdbc.JdbcSchedulerStore
import dev.nightjar.coordination.jdbc.JdbcWorkerPool
import dev.nightjar.coordination.scheduler.SimpleScheduler
import dev.nightjar.domainevent.jdbc.TransactionalConnectionSource
import dev.nightjar.domainevent.spi.TransactionalRunner
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Initialized
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Disposes
import jakarta.enterprise.inject.Produces
import jakarta.transaction.Status
import jakarta.transaction.UserTransaction
import javax.sql.DataSource

/**
 * Quarkus wiring for nightjar coordination — pure CDI + JTA, mirroring
 * [DomainEventProducers].
 */
@ApplicationScoped
class CoordinationProducers {

    @Produces
    @ApplicationScoped
    fun simpleScheduler(dataSource: DataSource, userTransaction: UserTransaction): SimpleScheduler {
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
        return SimpleScheduler.builder()
            .store(JdbcSchedulerStore(dataSource, connectionSource))
            .lock(JdbcProcessLock(dataSource))
            .workerPool(JdbcWorkerPool(dataSource))
            .transactionalRunner(transactionalRunner)
            .build()
    }

    /** Register jobs, then start — once, on application startup. */
    fun onStart(@Observes @Initialized(ApplicationScoped::class) ignored: Any, scheduler: SimpleScheduler) {
        scheduler.register("payment-reminder") { orderId, _ ->
            println("remind $orderId")
            null // or an Instant to fire again
        }
        scheduler.start()
    }

    fun close(@Disposes scheduler: SimpleScheduler) {
        scheduler.close()
    }
}
