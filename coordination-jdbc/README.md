# coordination-jdbc

Database-backed implementations of the [coordination](../coordination/)
primitives: `JdbcProcessLock`, `JdbcWorkerPool`, `JdbcSchedulerStore`.

**Runtime dependencies: none** (`java.sql`; bring your driver). Portable SQL,
verified on PostgreSQL and H2.

## Installation

```kotlin
dependencies {
    implementation("dev.nightjar:coordination-jdbc:0.1.0-SNAPSHOT")
}
```

## Schema

Contributed via the [nightjar migrations convention](../migrations/) (manifest
+ changelog + pristine SQL at
`dev/nightjar/coordination/jdbc/coordination-postgresql.sql`); applied
automatically by [`migrations-liquibase`](../migrations-liquibase/). Tables:
`process_lock`, `worker`, `worker_config`, `simple_scheduler`.

## Mechanics worth knowing

- **Lock acquisition** is an `INSERT` (losers hit the primary key); expired
  leases are taken over with one atomic conditional `UPDATE`. `expires_at` is
  stored directly — no vendor date arithmetic
- **Worker acquisition** locks the type's `worker_config` row (`FOR UPDATE`)
  to serialize competing claims; permits older than the TTL (default 10 min)
  are ignored and swept every 10 s
- **Scheduler upserts** join the caller's transaction via
  `TransactionalConnectionSource` — scheduling is atomic with your business
  change
- **`JdbcProcessLock`** can take a `TransactionalConnectionSource` too: acquired
  inside a transaction, a rollback frees the lock and a commit holds it; a
  contended acquire returns `false` without aborting the transaction. Outside a
  transaction it uses an autonomous connection with the lease TTL as the crash
  net

```kotlin
val lock = JdbcProcessLock(dataSource)
val pool = JdbcWorkerPool(dataSource)              // AutoCloseable (cleanup thread)
val scheduler = SimpleScheduler.builder()
    .store(JdbcSchedulerStore(dataSource, transactions))
    .lock(lock)
    .workerPool(pool)
    .transactionalRunner(transactions)
    .build()
```

Real-PostgreSQL integration tests: `devbox services up && ./gradlew :coordination-jdbc:integrationTest`.
