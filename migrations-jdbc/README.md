# migrations-jdbc

`DataMigrationStore` implementation over plain JDBC for the
[migrations](../migrations/) library.

**Runtime dependencies: none** (`java.sql`; bring your driver). Verified on
PostgreSQL and H2 — portable SQL, no vendor extensions in queries.

## Installation

```kotlin
dependencies {
    implementation("dev.nightjar:migrations-jdbc:0.1.0-SNAPSHOT")
}
```

## Schema

Ships its own tables through the nightjar migration convention (manifest +
changelog + pristine SQL at
`dev/nightjar/migrations/jdbc/data-migration-postgresql.sql`):
`data_migration`, `sequential_data_migration`, `data_migration_lock`.
Applied automatically by `migrations-liquibase`.

## Usage

```kotlin
val transactions = JdbcTransactions(dataSource)   // or your framework's adapters
val store = JdbcDataMigrationStore(dataSource, transactions)

val engine = DataMigrationEngine.builder()
    .store(store)
    .transactionalRunner(transactions)
    .build()
```

`enqueue(...)` joins the caller's transaction via
`TransactionalConnectionSource` — enqueueing rolls back with your business
data. Claiming uses optimistic conditional updates (multi-instance safe);
sequential ordering is protected by row locks on `data_migration_lock`.
