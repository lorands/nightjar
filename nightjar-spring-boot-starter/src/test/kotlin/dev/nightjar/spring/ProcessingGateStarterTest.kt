package dev.nightjar.spring

import dev.nightjar.coordination.worker.WorkerPool
import dev.nightjar.domainevent.DomainEventListener
import dev.nightjar.domainevent.DomainEventPublisher
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cluster-wide stop: suspending the event worker type
 * (concurrency 0) pauses delivery across the cluster; restoring it resumes
 * automatically — no events lost, none suspended.
 */
@SpringBootTest(
    classes = [TestApp::class, ProcessingGateStarterTest.Beans::class],
    properties = [
        "nightjar.coordination.worker.enabled=true",
        "nightjar.domain-event.worker-type=nightjar-domain-events",
    ],
)
class ProcessingGateStarterTest(
    private val publisher: DomainEventPublisher,
    private val workerPool: WorkerPool,
) {

    companion object {
        val delivered = AtomicInteger()
        var latch = CountDownLatch(1)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Beans {
        @Bean
        fun gatedListener(): DomainEventListener<OrderPlaced> = DomainEventListener {
            delivered.incrementAndGet()
            latch.countDown()
        }
    }

    @Test
    fun `suspending the worker type stops delivery, restoring it resumes`() {
        delivered.set(0)
        latch = CountDownLatch(1)

        workerPool.configure("nightjar-domain-events", 0) // cluster-wide stop
        assertTrue(workerPool.isSuspended("nightjar-domain-events"))

        publisher.publish(OrderPlaced("paused"))
        Thread.sleep(300) // many 50 ms relay polls
        assertEquals(0, delivered.get(), "no delivery while the worker type is suspended")

        workerPool.configure("nightjar-domain-events", 1) // resume
        assertTrue(latch.await(10, TimeUnit.SECONDS), "delivery must resume once the worker type is restored")
        assertEquals(1, delivered.get())
    }
}
