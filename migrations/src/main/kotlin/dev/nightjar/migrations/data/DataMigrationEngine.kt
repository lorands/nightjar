package dev.nightjar.migrations.data

import dev.nightjar.domainevent.spi.TransactionalRunner
import java.lang.System.Logger.Level
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous data-migration processing: entities are enqueued by id under a
 * type, and registered [DataMigrationHandler]s process them in batches.
 *
 * Two modes:
 * - [enqueue] — *parallel*: batches claimed atomically from the store, so any
 *   number of application instances divide the work; `priority` (lower first)
 *   orders types
 * - [enqueueSequential] — *strictly ordered*: one global queue processed in
 *   insertion order under a cluster-wide lock; a failing batch **blocks the
 *   sequential queue** (by design — order is the contract) and is retried on
 *   the next poll
 *
 * Handlers run inside the configured [TransactionalRunner]; rows are deleted
 * after success (at-least-once — handlers must be idempotent).
 *
 * ```
 * val engine = DataMigrationEngine.builder().store(store).build()
 * engine.register("reindex-orders") { _, ids -> orderIndexer.reindex(ids) }
 * engine.start()
 * engine.enqueue("reindex-orders", orderIds)
 * ```
 */
public class DataMigrationEngine private constructor(builder: Builder) : AutoCloseable {

    private val log = System.getLogger(DataMigrationEngine::class.java.name)

    private val store = requireNotNull(builder.store) { "store is required" }
    private val transactionalRunner = builder.transactionalRunner
    private val pollInterval = builder.pollInterval
    private val batchSize = builder.batchSize
    private val sequentialBatchSize = builder.sequentialBatchSize
    private val maxBatchesPerPoll = builder.maxBatchesPerPoll
    private val claimExpiry = builder.claimExpiry

    private val handlers = ConcurrentHashMap<String, DataMigrationHandler>()
    private val started = AtomicBoolean()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nightjar-data-migrations").apply { isDaemon = true }
    }

    /** Register the handler for [type]. One handler per type. */
    public fun register(type: String, handler: DataMigrationHandler) {
        val previous = handlers.putIfAbsent(type, handler)
        require(previous == null) { "A handler for type '$type' is already registered" }
    }

    /** Enqueue ids for parallel processing with default priority 100. */
    public fun enqueue(type: String, ids: Collection<String>) {
        enqueue(type, ids, DEFAULT_PRIORITY)
    }

    /** Enqueue ids for parallel processing; lower [priority] processes first. */
    public fun enqueue(type: String, ids: Collection<String>, priority: Int) {
        if (ids.isNotEmpty()) store.enqueue(type, ids, priority)
    }

    /** Append ids to the strictly-ordered sequential queue. */
    public fun enqueueSequential(type: String, ids: Collection<String>) {
        if (ids.isNotEmpty()) store.enqueueSequential(type, ids)
    }

    /** Start continuous polling. */
    public fun start() {
        check(!started.getAndSet(true)) { "DataMigrationEngine already started" }
        scheduler.scheduleWithFixedDelay(
            ::safePoll, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS,
        )
    }

    private fun safePoll() {
        try {
            pollOnce()
        } catch (e: Exception) {
            log.log(Level.ERROR, "Data-migration poll failed", e)
        }
    }

    // Visible for tests: one deterministic poll pass.
    internal fun pollOnce() {
        processParallel()
        processSequential()
    }

    private fun processParallel() {
        // Types that failed (or have no handler) sit out the rest of this poll —
        // they retry next poll while healthy types keep processing.
        val benchedTypes = mutableSetOf<String>()
        repeat(maxBatchesPerPoll) {
            val batch = store.claimNextBatch(UUID.randomUUID().toString(), batchSize, claimExpiry, benchedTypes)
                ?: return
            val handler = handlers[batch.type]
            if (handler == null) {
                log.log(Level.WARNING, "No handler registered for data-migration type '${batch.type}' — releasing batch")
                store.releaseClaim(batch, countFailure = false)
                benchedTypes.add(batch.type)
                return@repeat
            }
            try {
                transactionalRunner.run { handler.process(batch.type, batch.ids) }
                store.deleteClaimed(batch)
            } catch (e: Exception) {
                store.releaseClaim(batch, countFailure = true)
                benchedTypes.add(batch.type)
                log.log(Level.WARNING, "Data-migration batch failed (type=${batch.type}, size=${batch.ids.size}); will retry", e)
            }
        }
    }

    private fun processSequential() {
        while (true) {
            val type = store.peekNextSequentialType() ?: return
            var progressed = false
            try {
                store.withTypeLock(type) {
                    val ids = store.nextSequentialBatch(type, sequentialBatchSize)
                    if (ids.isEmpty()) return@withTypeLock // another instance drained it
                    val handler = handlers[type]
                        ?: throw IllegalStateException("No handler registered for sequential data-migration type '$type'")
                    transactionalRunner.run { handler.process(type, ids) }
                    store.deleteSequential(type, ids)
                    progressed = true
                }
            } catch (e: Exception) {
                // Strict ordering: the failing head blocks the sequential queue
                // until it succeeds. Loud on purpose.
                log.log(Level.ERROR, "Sequential data-migration head failed (type=$type) — queue blocked until it succeeds", e)
                return
            }
            if (!progressed) return
        }
    }

    override fun close() {
        scheduler.shutdown()
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
            scheduler.shutdownNow()
        }
    }

    public companion object {

        public const val DEFAULT_PRIORITY: Int = 100

        @JvmStatic
        public fun builder(): Builder = Builder()
    }

    public class Builder internal constructor() {
        internal var store: DataMigrationStore? = null
        internal var transactionalRunner: TransactionalRunner = TransactionalRunner.DIRECT
        internal var pollInterval: Duration = Duration.ofSeconds(5)
        internal var batchSize: Int = 200
        internal var sequentialBatchSize: Int = 25
        internal var maxBatchesPerPoll: Int = 250
        internal var claimExpiry: Duration = Duration.ofMinutes(10)

        public fun store(store: DataMigrationStore): Builder = apply { this.store = store }

        /** Wraps each handler invocation; adapt your transaction manager. Default: none. */
        public fun transactionalRunner(runner: TransactionalRunner): Builder = apply { this.transactionalRunner = runner }

        /** Poll cadence. Default 5 s. */
        public fun pollInterval(pollInterval: Duration): Builder = apply { this.pollInterval = pollInterval }

        /** Ids per parallel batch. Default 200. */
        public fun batchSize(batchSize: Int): Builder = apply { this.batchSize = batchSize }

        /** Ids per sequential batch. Default 25. */
        public fun sequentialBatchSize(size: Int): Builder = apply { this.sequentialBatchSize = size }

        /** Upper bound of parallel batches processed per poll. Default 250. */
        public fun maxBatchesPerPoll(max: Int): Builder = apply { this.maxBatchesPerPoll = max }

        /** Claims older than this count as abandoned (crashed instance). Default 10 min. */
        public fun claimExpiry(claimExpiry: Duration): Builder = apply { this.claimExpiry = claimExpiry }

        public fun build(): DataMigrationEngine = DataMigrationEngine(this)
    }
}
