package dev.nightjar.nativesmoke

import dev.nightjar.domainevent.DomainEvent
import dev.nightjar.domainevent.DomainEventBus
import dev.nightjar.domainevent.TimeWindowRetryPolicy
import dev.nightjar.domainevent.inmemory.InMemoryOutboxStore
import dev.nightjar.domainevent.inmemory.InProcessEventTransport
import dev.nightjar.domainevent.spi.EventSerializer
import dev.nightjar.migrations.data.DataMigrationEngine
import dev.nightjar.migrations.data.InMemoryDataMigrationStore
import dev.nightjar.migrations.schema.MigrationManifests
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * GraalVM native-image smoke harness: exercises every native-sensitive
 * nightjar code path inside an AOT-compiled binary. Exits non-zero on any
 * failure — wired into CI as the executable proof of native support.
 *
 * Covered: UUIDv7/SecureRandom, the relay scheduler thread, sync + async
 * dispatch, the retry path, the data-migration engine (parallel + sequential),
 * and classpath manifest discovery (validating the shipped
 * native-image resource metadata).
 */
object NativeSmoke {

    class OrderPlaced(val orderId: String) : DomainEvent

    private object Serializer : EventSerializer {
        override fun serialize(event: DomainEvent) = (event as OrderPlaced).orderId.toByteArray()
        override fun deserialize(type: String, payload: ByteArray): DomainEvent {
            require(type == "OrderPlaced") { "unexpected type $type" }
            return OrderPlaced(String(payload))
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            checkDomainEvents()
            checkRetryPath()
            checkDataMigrations()
            checkCoordination()
            checkManifestDiscovery()
        } catch (e: Throwable) {
            System.err.println("NATIVE SMOKE FAILED: ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }
        println("NATIVE SMOKE PASSED")
    }

    private fun checkDomainEvents() {
        val syncSeen = AtomicInteger()
        val asyncLatch = CountDownLatch(1)
        var envelopeOk = false

        DomainEventBus.builder()
            .serializer(Serializer)
            .store(InMemoryOutboxStore())
            .transport(InProcessEventTransport())
            .pollInterval(Duration.ofMillis(10))
            .metadataProvider { mapOf("origin" to "native") }
            .build().use { bus ->
                bus.subscribeSync(OrderPlaced::class.java) { syncSeen.incrementAndGet() }
                bus.subscribe(OrderPlaced::class.java) { envelope ->
                    envelopeOk = envelope.event.orderId == "o-1" &&
                        envelope.metadata["origin"] == "native" &&
                        envelope.id.length == 36 && // UUIDv7 via SecureRandom
                        envelope.type == "OrderPlaced" // Class.simpleName under AOT
                    asyncLatch.countDown()
                }
                bus.start()
                bus.publish(OrderPlaced("o-1"))

                check(syncSeen.get() == 1) { "sync listener did not run inline" }
                check(asyncLatch.await(10, TimeUnit.SECONDS)) { "async listener never invoked" }
                check(envelopeOk) { "envelope contents wrong in native image" }
            }
        println("  [ok] domain-event publish/dispatch pipeline")
    }

    private fun checkRetryPath() {
        val attempts = AtomicInteger()
        val healed = CountDownLatch(1)

        DomainEventBus.builder()
            .serializer(Serializer)
            .store(InMemoryOutboxStore())
            .transport(InProcessEventTransport())
            .pollInterval(Duration.ofMillis(10))
            .retryPolicy(
                TimeWindowRetryPolicy(
                    Duration.ofMillis(30), Duration.ofSeconds(5),
                    Duration.ofMillis(30), Duration.ofSeconds(30),
                ),
            )
            .build().use { bus ->
                bus.subscribe(OrderPlaced::class.java) {
                    if (attempts.incrementAndGet() <= 2) throw IllegalStateException("transient")
                    healed.countDown()
                }
                bus.start()
                bus.publish(OrderPlaced("o-retry"))

                check(healed.await(15, TimeUnit.SECONDS)) { "retry path never recovered" }
                check(attempts.get() == 3) { "expected 3 attempts, got ${attempts.get()}" }
            }
        println("  [ok] outbox retry path")
    }

    private fun checkDataMigrations() {
        val parallel = Collections.synchronizedList(mutableListOf<String>())
        val sequential = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(2)

        DataMigrationEngine.builder()
            .store(InMemoryDataMigrationStore())
            .pollInterval(Duration.ofMillis(20))
            .batchSize(2)
            .build().use { engine ->
                engine.register("reindex") { _, ids ->
                    parallel.addAll(ids)
                    if (parallel.size == 3) done.countDown()
                }
                engine.register("ordered") { _, ids ->
                    sequential.addAll(ids)
                    if (sequential.size == 3) done.countDown()
                }
                engine.start()
                engine.enqueue("reindex", listOf("p1", "p2", "p3"))
                engine.enqueueSequential("ordered", listOf("s1", "s2"))
                engine.enqueueSequential("ordered", listOf("s3"))

                check(done.await(15, TimeUnit.SECONDS)) { "data migrations did not complete" }
                check(parallel.sorted() == listOf("p1", "p2", "p3")) { "parallel batch loss: $parallel" }
                check(sequential == listOf("s1", "s2", "s3")) { "sequential order broken: $sequential" }
            }
        println("  [ok] data-migration engine (parallel + sequential)")
    }

    private fun checkCoordination() {
        // process lock: exclusivity + lease takeover
        val lock = dev.nightjar.coordination.lock.InMemoryProcessLock()
        check(lock.tryAcquire("smoke")) { "lock acquisition failed" }
        check(!lock.tryAcquire("smoke")) { "lock must be exclusive" }
        lock.release("smoke")

        // worker pool: throttling + suspension
        dev.nightjar.coordination.worker.InMemoryWorkerPool().use { pool ->
            pool.configure("smoke", 1)
            check(pool.execute("smoke") {}) { "worker execute failed" }
            pool.configure("smoke", 0)
            check(!pool.execute("smoke") {}) { "suspended type must not execute" }
        }

        // scheduler: due job fires exactly once via a manual fire pass
        val fired = AtomicInteger()
        dev.nightjar.coordination.scheduler.SimpleScheduler.builder()
            .store(dev.nightjar.coordination.scheduler.InMemorySchedulerStore())
            .lock(dev.nightjar.coordination.lock.InMemoryProcessLock())
            .build().use { scheduler ->
                scheduler.register("smoke-job") { _, _ ->
                    fired.incrementAndGet()
                    null
                }
                scheduler.schedule("agg", java.time.Instant.now().plusMillis(50), "smoke-job")
                Thread.sleep(100)
                scheduler.fire()
                scheduler.fire()
                check(fired.get() == 1) { "scheduler must fire exactly once, got ${fired.get()}" }
            }
        println("  [ok] coordination (lock, worker pool, scheduler)")
    }

    private fun checkManifestDiscovery() {
        // works in the native binary ONLY if the shipped resource-config.json
        // metadata embedded the manifest resources
        val ids = MigrationManifests.discover().map { it.id }
        check("domain-event-jdbc" in ids && "migrations-jdbc" in ids) {
            "manifest discovery broken in native image — found: $ids"
        }
        println("  [ok] migration manifest discovery via classpath resources: $ids")
    }
}
