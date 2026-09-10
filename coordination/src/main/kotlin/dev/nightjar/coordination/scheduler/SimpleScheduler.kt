package dev.nightjar.coordination.scheduler

import dev.nightjar.coordination.lock.ProcessLock
import dev.nightjar.coordination.worker.WorkerPool
import dev.nightjar.domainevent.spi.TransactionalRunner
import java.lang.System.Logger.Level
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent one-shot timers for aggregates: schedule a job for an aggregate
 * at an absolute time; the job's return value reschedules it; failures retry
 * after a delay. Cluster-safe: the polling pass runs under a [ProcessLock],
 * and entries are deleted *before* execution — one fire per due entry, even
 * across crashing instances (at-least-once overall: jobs must be idempotent).
 *
 * ```
 * val scheduler = SimpleScheduler.builder().store(store).lock(lock).build()
 * scheduler.register("payment-reminder") { orderId, ctx ->
 *     if (remind(orderId)) null                       // done
 *     else clock.instant().plus(Duration.ofDays(1))   // again tomorrow
 * }
 * scheduler.start()
 * scheduler.schedule("order-42", tomorrowNoon, "payment-reminder")
 * ```
 *
 * Scheduling the same `(aggregateId, jobType)` again moves its fire time
 * (upsert). `schedule` joins the caller's transaction when the store supports
 * it. Optionally wrap firing in a [WorkerPool] type so operations can suspend
 * the scheduler cluster-wide (`configure(type, 0)`).
 */
public class SimpleScheduler private constructor(builder: Builder) : AutoCloseable {

    private val log = System.getLogger(SimpleScheduler::class.java.name)

    private val store = requireNotNull(builder.store) { "store is required" }
    private val lock = requireNotNull(builder.lock) { "lock is required" }
    private val transactionalRunner = builder.transactionalRunner
    private val workerPool = builder.workerPool
    private val workerType = builder.workerType
    private val clock = builder.clock
    private val pollInterval = builder.pollInterval
    private val retryDelay = builder.retryDelay
    private val batchSize = builder.batchSize
    private val fireLockTtl = builder.fireLockTtl

    private val jobs = ConcurrentHashMap<String, ScheduledJob>()
    private val started = AtomicBoolean()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nightjar-simple-scheduler").apply { isDaemon = true }
    }

    /** Register the job for [jobType]. One job per type. */
    public fun register(jobType: String, job: ScheduledJob) {
        val previous = jobs.putIfAbsent(jobType, job)
        require(previous == null) { "A job for type '$jobType' is already registered" }
    }

    /**
     * Schedule (or move) the timer for `(aggregateId, jobType)`.
     * [fireAfter] must be in the future.
     */
    @JvmOverloads
    public fun schedule(aggregateId: String, fireAfter: Instant, jobType: String, context: String? = null) {
        require(fireAfter.isAfter(clock.instant())) { "Cannot schedule in the past: $fireAfter" }
        store.upsert(ScheduledEntry(aggregateId, jobType, fireAfter, context))
    }

    /** Cancel the timer for `(aggregateId, jobType)`, if any. */
    public fun cancel(aggregateId: String, jobType: String) {
        store.delete(aggregateId, jobType)
    }

    /** Start the polling loop. */
    public fun start() {
        check(!started.getAndSet(true)) { "SimpleScheduler already started" }
        scheduler.scheduleWithFixedDelay(
            ::safeFire, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS,
        )
    }

    private fun safeFire() {
        try {
            fire()
        } catch (e: Exception) {
            log.log(Level.ERROR, "Scheduler fire pass failed", e)
        }
    }

    /**
     * Run one fire pass immediately (the polling loop calls this on cadence).
     * Public for manual triggering and deterministic tests.
     */
    public fun fire() {
        val pool = workerPool
        if (pool != null) {
            pool.execute(workerType) { firePass() } // suspendable via configure(type, 0)
        } else {
            firePass()
        }
    }

    private fun firePass() {
        // Single-fire across the cluster: one instance polls at a time. The
        // lease recovers the lock if an instance dies mid-pass.
        if (!lock.tryAcquire(FIRE_LOCK, fireLockTtl)) return
        try {
            val due = store.due(clock.instant(), batchSize)
            for (entry in due) {
                // delete BEFORE execute, committed immediately: a crash mid-job
                // loses at most this pass's entry but never double-fires.
                // Deliberately stricter than holding the delete in a pass-long
                // transaction: a crash there rolls deletes back while committed
                // job TXs survive, double-firing them.
                store.delete(entry.aggregateId, entry.jobType)
                fire(entry)
            }
        } finally {
            lock.release(FIRE_LOCK)
        }
    }

    private fun fire(entry: ScheduledEntry) {
        val job = jobs[entry.jobType]
        if (job == null) {
            log.log(
                Level.WARNING,
                "No job registered for type '${entry.jobType}' — rescheduling ${entry.aggregateId} in $retryDelay",
            )
            store.upsert(ScheduledEntry(entry.aggregateId, entry.jobType, clock.instant().plus(retryDelay), entry.context))
            return
        }
        try {
            var next: Instant? = null
            transactionalRunner.run { next = job.run(entry.aggregateId, entry.context) }
            val rescheduleAt = next
            if (rescheduleAt != null) {
                store.upsert(ScheduledEntry(entry.aggregateId, entry.jobType, rescheduleAt, entry.context))
            }
        } catch (e: Exception) {
            store.upsert(ScheduledEntry(entry.aggregateId, entry.jobType, clock.instant().plus(retryDelay), entry.context))
            log.log(
                Level.WARNING,
                "Scheduled job '${entry.jobType}' failed for ${entry.aggregateId}; retrying in $retryDelay", e,
            )
        }
    }

    override fun close() {
        scheduler.shutdown()
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
            scheduler.shutdownNow()
        }
    }

    public companion object {

        /** Global lock id serializing fire passes cluster-wide. */
        public const val FIRE_LOCK: String = "nightjar-simple-scheduler"

        @JvmStatic
        public fun builder(): Builder = Builder()
    }

    public class Builder internal constructor() {
        internal var store: SchedulerStore? = null
        internal var lock: ProcessLock? = null
        internal var transactionalRunner: TransactionalRunner = TransactionalRunner.DIRECT
        internal var workerPool: WorkerPool? = null
        internal var workerType: String = "simple-scheduler"
        internal var clock: Clock = Clock.systemUTC()
        internal var pollInterval: Duration = Duration.ofSeconds(60)
        internal var retryDelay: Duration = Duration.ofMinutes(5)
        internal var batchSize: Int = 100
        internal var fireLockTtl: Duration = Duration.ofMinutes(10)

        public fun store(store: SchedulerStore): Builder = apply { this.store = store }
        public fun lock(lock: ProcessLock): Builder = apply { this.lock = lock }

        /** Wraps each job invocation; adapt your transaction manager. Default: none. */
        public fun transactionalRunner(runner: TransactionalRunner): Builder = apply { this.transactionalRunner = runner }

        /** Optional: wrap fire passes in a worker type so operations can suspend the scheduler. */
        @JvmOverloads
        public fun workerPool(pool: WorkerPool, workerType: String = "simple-scheduler"): Builder = apply {
            this.workerPool = pool
            this.workerType = workerType
        }

        public fun clock(clock: Clock): Builder = apply { this.clock = clock }

        /** Fire-pass poll cadence. Default 60 s. */
        public fun pollInterval(pollInterval: Duration): Builder = apply { this.pollInterval = pollInterval }

        /** Delay before retrying a failed (or unregistered) job. Default 5 min. */
        public fun retryDelay(retryDelay: Duration): Builder = apply { this.retryDelay = retryDelay }

        /** Max due entries per fire pass. Default 100. */
        public fun batchSize(batchSize: Int): Builder = apply { this.batchSize = batchSize }

        /** Lease on the global fire lock — crashed instances self-heal. Default 10 min. */
        public fun fireLockTtl(fireLockTtl: Duration): Builder = apply { this.fireLockTtl = fireLockTtl }

        public fun build(): SimpleScheduler = SimpleScheduler(this)
    }
}
