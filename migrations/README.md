# migrations

DDD-friendly migrations for JVM applications, split into the two concerns they
really are:

1. **Schema migrations** — pristine `.sql` files tracked by Liquibase
   changelogs and discovered through a classpath manifest convention; applied
   at *deploy* time (see [`migrations-liquibase`](../migrations-liquibase/))
2. **Data migrations** — continuous, batched reprocessing of entities by id
   ([`DataMigrationEngine`]): typed handlers, priorities, a strictly-ordered
   sequential mode, multi-instance work distribution

**Runtime dependencies: none** (this module + `domain-event` are both
`kotlin-stdlib` only).

## Installation

```kotlin
dependencies {
    implementation("com.lorands.nightjar:migrations:<version>")
    implementation("com.lorands.nightjar:migrations-jdbc:<version>")      // JDBC store
    // deploy-time only (init container / CLI):
    implementation("com.lorands.nightjar:migrations-liquibase:<version>")
}
```

## Schema migrations — the convention

A module (yours or nightjar's) contributes schema by shipping:

```
META-INF/nightjar/migrations.properties   # id=..., changelog=..., order=...
db/changelog/<id>-changelog.xml           # Liquibase changesets — sqlFile refs only
<any path>/*.sql                          # pristine SQL, never transformed
```

`MigrationManifests.discover()` finds all contributions;
`migrations-liquibase` applies them in order. SQL stays SQL.

## Data migrations

```kotlin
val engine = DataMigrationEngine.builder()
    .store(JdbcDataMigrationStore(dataSource, transactions))
    .transactionalRunner(transactions)
    .build()

engine.register("reindex-orders") { _, ids -> orderIndexer.reindex(ids) }
engine.start()

engine.enqueue("reindex-orders", orderIds)            // parallel, batched, multi-instance
engine.enqueueSequential("recalculate", accountIds)   // strict global order
```

- Batches are claimed atomically with a claim token + expiry — run any number
  of instances; crashed claims are recovered
- A failing parallel batch retries next poll without blocking other types;
  a failing **sequential** head blocks the sequential queue by design
- Handlers run inside your `TransactionalRunner` and must be idempotent
  (at-least-once)

Full documentation: [user manual](../docs/migrations-manual.adoc) ·
[design notes](../docs/migrations-design.md) ·
runnable [examples](../examples/)
