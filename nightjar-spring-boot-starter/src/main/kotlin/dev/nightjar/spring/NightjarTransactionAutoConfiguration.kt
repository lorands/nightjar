package dev.nightjar.spring

import dev.nightjar.domainevent.jdbc.TransactionalConnectionSource
import dev.nightjar.domainevent.spi.TransactionalRunner
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * The bridge between nightjar's two transaction SPIs and Spring's transaction
 * management — the heart of the starter: publishing, enqueueing and
 * scheduling inside `@Transactional` code becomes atomic with the business
 * change, and async listeners/handlers/jobs run in their own Spring
 * transactions.
 */
@AutoConfiguration(
    afterName = [
        // Boot 3.x and Boot 4.x locations — the unknown one is ignored on each generation
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
    ],
)
public class NightjarTransactionAutoConfiguration {

    /** Joins nightjar appends/upserts to the active Spring-managed transaction. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource::class)
    public fun nightjarTransactionalConnectionSource(dataSource: DataSource): TransactionalConnectionSource =
        TransactionalConnectionSource {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                DataSourceUtils.getConnection(dataSource)
            } else {
                null
            }
        }

    /** Runs each async listener/handler/job in its own new Spring transaction. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PlatformTransactionManager::class)
    public fun nightjarTransactionalRunner(transactionManager: PlatformTransactionManager): TransactionalRunner {
        val template = TransactionTemplate(transactionManager)
        template.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        return TransactionalRunner { action -> template.executeWithoutResult { action.run() } }
    }
}
