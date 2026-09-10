# migrations — Design

Minimalist schema- and data-migration support.
Hard constraints: [REQUIREMENTS.md](../REQUIREMENTS.md).

Two separate concerns:

1. **Schema migrations** — DDL applied at deploy time
2. **Data migrations** — application-aware reprocessing of entities after schema changes

## Existing solutions considered (REQUIREMENTS.md #5)

| Solution | Verdict |
|---|---|
| **Liquibase** | **Adopted** for schema execution (user decision): OSS, very actively maintained, supports raw SQL untransformed, and is a *deploy-time* tool — applications never carry it at runtime |
| Flyway | Equally capable, but Liquibase chosen: the changelog + `sqlFile` pattern keeps SQL pristine, and changelog aggregation across modules is richer |
| Spring Boot/Quarkus Liquibase starters | Framework-bound auto-config; we instead expose plain changelogs that those starters CAN consume |
| Debezium/ETL tools for data migrations | Infrastructure-heavy; a typed-handler model is application-aware and far leaner |

## Locked decisions

| # | Decision |
|---|---|
| 1 | **SQL stays SQL** (user requirement): migrations are pristine `.sql` files referenced from Liquibase changelogs via `sqlFile` — never transformed into XML change types |
| 2 | Liquibase is confined to `migrations-liquibase` (deploy-time module); `migrations` core and contributing modules have zero Liquibase dependency |
| 3 | Library modules contribute schema via a classpath manifest `META-INF/nightjar/migrations.properties` (`id`, `changelog`, `order`); discovery is ~40 lines of zero-dep code |
| 4 | Data migrations are typed handlers with priority batches, a sequential (strict-order) mode, continuous polling and delete-on-success |
| 5 | Work distribution via atomic DB claiming (claim token + expiry), not broker re-publication — fewer moving parts, broker-agnostic, multi-instance safe, and no message broker required |
| 6 | `migrations` core depends on `domain-event` (zero external deps either way) to reuse the `TransactionalRunner` SPI and compose with event-driven enqueueing |

## Modules

| Module | External deps | Content |
|---|---|---|
| `migrations` | none | manifest discovery; data-migration engine + SPIs; in-memory store |
| `migrations-jdbc` | none (`java.sql`) | JDBC `DataMigrationStore`; its own schema shipped via the manifest convention (dogfooding) |
| `migrations-liquibase` | `org.liquibase:liquibase-core` | `LiquibaseMigrator`: aggregates discovered changelogs, runs `update`; CLI main for init containers |

## Schema migrations — the convention

A module contributing schema ships three things:

```
src/main/resources/
├── META-INF/nightjar/migrations.properties     # id=..., changelog=..., order=...
├── db/changelog/<id>-changelog.xml             # Liquibase changesets, sqlFile refs ONLY
└── <module's own path>/*.sql                   # pristine SQL, never transformed
```

Execution options for the host application (all consume the same files):

1. `migrations-liquibase`'s `LiquibaseMigrator` / CLI — init container or deploy step (recommended, Cloud-Native)
2. The host's own Spring Boot / Quarkus Liquibase integration including the changelogs directly
3. Plain Liquibase CLI with `--changelog-file` pointing at an aggregate

## Data migrations — the engine

```
enqueue(type, ids, priority)        → rows in data_migration (joins caller's TX)
enqueueSequential(type, ids)        → rows in sequential_data_migration (ordered by row_index)

DataMigrationEngine [poll, default 5 s]
  ├── parallel:   claim up to batchSize rows of one type (atomic claim token,
  │               stale claims expire) → handler.process(type, ids) in a TX
  │               → delete on success | release claim on failure (attempts++)
  └── sequential: cluster-wide lock per type → strictly ordered batches;
                  a failing head BLOCKS the queue (by design — order is the contract)
```

- Handlers: `register(type, DataMigrationHandler)` — explicit registration, no scanning
- Batches per poll and batch size configurable (defaults: 200 parallel / 25 sequential)
- Composition with domain-event: subscribe a listener that calls `enqueue(...)` — event-driven enqueueing without coupling the two modules

### Table schemas (shipped via the convention, PostgreSQL)

```sql
CREATE TABLE data_migration (
  id         VARCHAR(100) NOT NULL,
  type       VARCHAR(100) NOT NULL,
  priority   INT          NOT NULL DEFAULT 100,
  attempts   INT          NOT NULL DEFAULT 0,
  claimed_by VARCHAR(64),
  claimed_at TIMESTAMP
);
CREATE TABLE sequential_data_migration (
  id        VARCHAR(100) NOT NULL,
  type      VARCHAR(100) NOT NULL,
  row_index BIGINT       NOT NULL
);
```

## Design choices and the alternatives rejected

| Common alternative | nightjar | Why |
|---|---|---|
| Batches re-published to broker queues for distribution | atomic DB claiming with expiry | broker-agnostic, fewer moving parts, same multi-instance semantics |
| Framework scheduling + broker listeners + an ORM | JDK scheduler + plain JDBC | zero framework deps |
| Handlers discovered as framework beans by type | explicit `register(type, handler)` | no scanning, Java-friendly |
| Liquibase changesets maintained per-ticket by hand | the same per-ticket directory layout, formalized as the manifest convention | a proven layout, made discoverable across modules |
