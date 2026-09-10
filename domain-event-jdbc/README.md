# domain-event-jdbc

`OutboxStore` implementation over plain JDBC for the
[domain-event](../domain-event/) library, plus `JdbcTransactions` — minimal
thread-bound transaction management for applications without a framework.

**Runtime dependencies: none** (`java.sql` is part of the JDK; you supply the driver).
Verified on PostgreSQL and H2; the SQL is portable (no vendor extensions).

## Installation

```kotlin
dependencies {
    implementation("dev.nightjar:domain-event-jdbc:0.1.0-SNAPSHOT")
    runtimeOnly("org.postgresql:postgresql:<version>") // your driver
}
```

## Schema

The module contributes its schema through the
[nightjar migrations convention](../migrations/): a manifest
(`META-INF/nightjar/migrations.properties`) + Liquibase changelog referencing
the pristine DDL at `dev/nightjar/domainevent/jdbc/domain-event-postgresql.sql`.
Running [`migrations-liquibase`](../migrations-liquibase/) at deploy time
applies it automatically; alternatively include the changelog in your own
Liquibase setup.

Two tables: `domain_event` (transient outbox — rows are deleted after
successful processing) and `domain_event_lock` (row-lock table backing
`sequenceKey` serialization).

## Usage

```kotlin
// Framework-free: JdbcTransactions is both the TransactionalRunner and the
// TransactionalConnectionSource — listeners, business data and outbox share TXs.
val transactions = JdbcTransactions(dataSource)
val store = JdbcOutboxStore(dataSource, transactions)

val bus = DomainEventBus.builder()
    .serializer(mySerializer)
    .store(store)
    .transport(myTransport)
    .transactionalRunner(transactions)
    .build()

transactions.run {
    // business JDBC work via transactions.current()
    bus.publish(OrderPlaced("order-1"))   // same transaction
}
```

With **Spring** or **Quarkus**, adapt their transaction management to the two
SPIs instead — complete wiring examples in [`examples/`](../examples/) and the
[user manual](../docs/domain-event-manual.adoc).

## Behavior notes

- Claiming is an atomic conditional `UPDATE` — safe with any number of competing consumers/instances
- `sequenceKey` locks are `SELECT ... FOR UPDATE` row locks — cross-instance, held for the duration of processing
- Timestamps are stored as UTC `TIMESTAMP`
- Custom table names via constructor parameters
