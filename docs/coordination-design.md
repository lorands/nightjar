# coordination — Design

Minimalist cluster-coordination primitives for multi-instance JVM
applications. Hard constraints: [REQUIREMENTS.md](../REQUIREMENTS.md).

Three primitives, one cohesive domain:

| Primitive | Essence |
|---|---|
| **Process lock** | Cross-instance named mutex — existence-based, non-blocking, non-reentrant |
| **Worker pool** | Cross-instance concurrency throttle: DB rows = held permits, `concurrency = 0` = suspended |
| **Simple scheduler** | Persistent one-shot timers keyed `(aggregateId, jobType)`; jobs reschedule by return value; cluster-single-fire |

Dependency chain: scheduler → lock (+ optional worker pool), worker pool → lock-free (config row locking suffices).

## Existing solutions considered (REQUIREMENTS.md #5)

| Solution | Why not adopted |
|---|---|
| ShedLock | Lock-around-@Scheduled only — no persistent per-aggregate timers, framework-oriented integrations |
| Quartz | Heavyweight: its own threading model + an 11-table schema — far more machinery than persistent per-aggregate timers need |
| db-scheduler | Closest match and well-maintained, but brings its own execution model + serialization; we need specific semantics (delete-before-execute, return-value rescheduling) and SPI alignment with nightjar |
| Redisson / ZooKeeper / etcd locks | New infrastructure dependency — the database is already there |
| JobRunr | Framework-flavored, storage formats + dashboards far beyond small-and-lean |

## Locked decisions

| # | Decision |
|---|---|
| 1 | One module pair: `coordination` (zero deps) + `coordination-jdbc` |
| 2 | **Lease-based lock expiry**: locks carry `acquired_at` + optional TTL; expired locks are atomically taken over. Never-expiring locks wedge the cluster when a holder crashes — and containers crash (REQUIREMENTS #4). TTL is per-acquire; `null` = never expires |
| 3 | **Worker vocabulary** (worker, worker type, concurrency, suspension) — familiar operational language; docs explain it is a distributed throttle |
| 4 | Timing constants ship as configurable defaults: fire poll 60 s, failed-job retry +5 min, worker permit TTL 10 min, cleanup 10 s |

## Design choices and the alternatives rejected

| Common alternative | nightjar | Why |
|---|---|---|
| `schedule()` publishes a broker message; a listener upserts the row | `schedule()` upserts the store directly, joining the caller's transaction | scheduling becomes atomic with the business change; one less moving part; broker-agnostic |
| Jobs as framework beans keyed by class FQN, context deserialized by reflecting on generics | explicit `register(jobType, job)`; context is an **opaque `String`** the app (de)serializes | no framework, no reflection, native-image-friendly — the same move as event types |
| `lock(id)` throws on conflict | `tryAcquire(id[, ttl]): Boolean` (+ throwing `acquire`, `withLock` convenience) | non-exceptional control flow, Java-friendly |
| No lock expiry; manual SQL cleanup | lease TTL + atomic takeover + `releaseStale` admin op | crashed holders self-heal |
| Worker cleanup driven by a framework's scheduling annotation | JDK `ScheduledExecutorService` inside the pool implementation | zero framework deps |
| Tracing baked into every call | `System.Logger` + composition with observers where needed | consistent with the rest of nightjar |
| Fire-pass deletes inside the pass-long transaction: a crash mid-pass rolls them back while committed `REQUIRES_NEW` job TXs survive — jobs can **double-fire** | delete commits immediately before execution: a crash in that window **drops at most one entry** (the +5 min retry row covers failures, not crashes) | strict single-fire is the safer reading of delete-before-execute; a double side effect is worse than one lost timer |

Guaranteed behaviors: delete-before-execute single-fire idempotency, `(aggregateId, jobType)` upsert-overwrite scheduling, reschedule-by-return-value, +5 min retry on job failure, fire under a global lock, optional worker-pool wrapping of `fire()` so the scheduler can be suspended via `concurrency = 0`, permit counting against live (non-expired) workers.

**Transactional lock binding (opt-in).** `JdbcProcessLock` accepts a
`TransactionalConnectionSource`; when a transaction is active it
acquires/releases on that connection, so the lock row is an `INSERT` in the
caller's transaction — a rollback frees the lock and a commit holds it. The
contended `INSERT` is savepoint-wrapped so a "held" result never aborts the
transaction. Without a source (or outside a transaction — e.g. the scheduler's
fire-lock on its polling thread) it uses an autonomous connection and the lease
TTL is the only crash net. This re-adds the one capability our initial
lease-only lock had dropped.

## API sketch

```kotlin
public interface ProcessLock {
    public fun tryAcquire(id: String, ttl: Duration? = null): Boolean
    public fun acquire(id: String, ttl: Duration? = null)        // throws if held
    public fun exists(id: String): Boolean                       // held and not expired
    public fun release(id: String)
    public fun withLock(id: String, ttl: Duration? = null, action: Runnable): Boolean
}

public interface WorkerPool : AutoCloseable {
    public fun newWorker(type: String): String?                  // permit uuid or null
    public fun terminate(uuid: String)
    public fun execute(type: String, task: Runnable): Boolean    // acquire→run→release
    public fun configure(type: String, concurrency: Int)         // 0 = suspended
    public fun isSuspended(type: String): Boolean
}

public fun interface ScheduledJob {
    public fun run(aggregateId: String, context: String?): Instant?  // null = done
}
// SimpleScheduler: register(jobType, job) / schedule(aggregateId, fireAfter, jobType, context)
//                  / cancel(aggregateId, jobType) / start() / close()
```

## Table schemas (shipped via the migrations convention, PostgreSQL)

```sql
CREATE TABLE process_lock (
  id          VARCHAR(250) PRIMARY KEY,
  owner       VARCHAR(100),          -- diagnostics
  acquired_at TIMESTAMP NOT NULL,    -- UTC
  expires_at  TIMESTAMP              -- UTC; NULL = never expires
);
-- expires_at (not ttl) is stored so lease takeover is one portable
-- conditional UPDATE — no vendor-specific date arithmetic
CREATE TABLE worker (
  uuid    VARCHAR(36)  PRIMARY KEY,
  type    VARCHAR(100) NOT NULL,
  created TIMESTAMP    NOT NULL      -- UTC; permit expires after TTL
);
CREATE TABLE worker_config (
  type        VARCHAR(100) PRIMARY KEY,
  concurrency INT NOT NULL
);
CREATE TABLE simple_scheduler (
  aggregate_id VARCHAR(250) NOT NULL,
  job_type     VARCHAR(250) NOT NULL,
  fire_after   TIMESTAMP    NOT NULL, -- UTC
  context      TEXT,
  PRIMARY KEY (aggregate_id, job_type)
);
```

## Composition with the rest of nightjar

- `domain-event`'s `OutboxStore.withSequenceLock` can delegate to `ProcessLock`
  when an app needs one shared lock namespace
- Scheduler jobs publishing domain events: `schedule()` and `publish()` in the
  same transaction compose naturally through the shared `TransactionalRunner` /
  `TransactionalConnectionSource` story
- Suspending event/data-migration processing operationally: configure a worker
  type to 0 and wrap the relevant work in `workerPool.execute` at wiring time
