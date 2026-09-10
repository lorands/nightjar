package dev.nightjar.spring

import dev.nightjar.coordination.lock.ProcessLock
import dev.nightjar.coordination.scheduler.SimpleScheduler
import dev.nightjar.migrations.data.DataMigrationEngine
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The migrations and coordination auto-configurations: handler/job beans are
 * discovered and the engines run on the application's DataSource.
 */
@SpringBootTest(
    classes = [TestApp::class, MigrationsAndCoordinationStarterTest.Beans::class],
    properties = [
        "nightjar.migrations.data.enabled=true",
        "nightjar.migrations.data.poll-interval=50ms",
        "nightjar.coordination.scheduler.enabled=true",
        "nightjar.coordination.scheduler.poll-interval=100ms",
    ],
)
class MigrationsAndCoordinationStarterTest(
    private val engine: DataMigrationEngine,
    private val scheduler: SimpleScheduler,
    private val processLock: ProcessLock,
) {

    companion object {
        val migrated: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val migrationDone = CountDownLatch(1)
        val jobFired = CountDownLatch(1)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Beans {

        @Bean
        fun reindexHandler(): DataMigrationHandlerBean = object : DataMigrationHandlerBean {
            override fun type() = "reindex"
            override fun process(ids: List<String>) {
                migrated.addAll(ids)
                migrationDone.countDown()
            }
        }

        @Bean
        fun reminderJob(): ScheduledJobBean = object : ScheduledJobBean {
            override fun jobType() = "reminder"
            override fun run(aggregateId: String, context: String?): Instant? {
                jobFired.countDown()
                return null
            }
        }
    }

    @Test
    fun `data migration handler beans are registered and process enqueued work`() {
        engine.enqueue("reindex", listOf("o-1", "o-2"))
        assertTrue(migrationDone.await(10, TimeUnit.SECONDS), "handler bean was not invoked")
        assertTrue(migrated.containsAll(listOf("o-1", "o-2")))
    }

    @Test
    fun `scheduled job beans are registered and fire`() {
        scheduler.schedule("order-1", Instant.now().plusMillis(150), "reminder")
        assertTrue(jobFired.await(10, TimeUnit.SECONDS), "job bean was not invoked")
    }

    @Test
    fun `process lock bean works against the application datasource`() {
        assertTrue(processLock.tryAcquire("starter-test-lock"))
        assertFalse(processLock.tryAcquire("starter-test-lock"))
        processLock.release("starter-test-lock")
    }
}
