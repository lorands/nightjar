# coordination

Cluster-coordination primitives for multi-instance JVM applications: a
process lock, a worker pool and a scheduler, all framework-free.

**Runtime dependencies: none** (this module + `domain-event` are both
`kotlin-stdlib` only).

| Primitive | What it gives you |
|---|---|
| `ProcessLock` | Cross-instance named mutex; optional **lease TTL** so crashed holders self-heal |
| `WorkerPool` | Cross-instance concurrency throttle; `configure(type, 0)` suspends a work type cluster-wide |
| `SimpleScheduler` | Persistent one-shot timers per aggregate; jobs reschedule by return value; cluster-single-fire |

## Installation

```kotlin
dependencies {
    implementation("dev.nightjar:coordination:0.1.0-SNAPSHOT")
    implementation("dev.nightjar:coordination-jdbc:0.1.0-SNAPSHOT") // DB-backed impls
}
```

## Usage

```kotlin
// mutex with a 30-minute lease — a crashed holder can't wedge the cluster
lock.withLock("monthly-invoicing", Duration.ofMinutes(30)) { generateInvoices() }

// at most 2 concurrent catalog imports across ALL instances
pool.configure("catalog-import", 2)
pool.execute("catalog-import") { importCatalog() }   // false = at capacity, skip

// a reminder for THIS order, three days from now — survives restarts
scheduler.register("payment-reminder") { orderId, ctx ->
    if (remind(orderId)) null else Instant.now().plus(Duration.ofDays(1)) // again tomorrow
}
scheduler.start()
scheduler.schedule(order.id, Instant.now().plus(Duration.ofDays(3)), "payment-reminder")
```

Scheduling the same `(aggregateId, jobType)` again *moves* the timer; `cancel`
removes it. `schedule()` joins the caller's transaction with the JDBC store.
Jobs are at-least-once — keep them idempotent. A scheduler wrapped in a
`WorkerPool` type can be suspended operationally (`configure(type, 0)`).

In-memory implementations of all three ship here for tests and
single-instance apps. Full documentation:
[user manual](../docs/coordination-manual.adoc) ·
[design notes](../docs/coordination-design.md)
