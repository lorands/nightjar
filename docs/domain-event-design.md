# domain-event — Design

Minimalist, framework-free domain events with a transactional outbox.
Hard constraints: [REQUIREMENTS.md](../REQUIREMENTS.md).

> This document is the *rationale*. For a line-by-line tour of the
> implementation, see [domain-event-internals.md](domain-event-internals.md);
> for the user-facing guide, [domain-event-manual.adoc](domain-event-manual.adoc).

## Existing solutions considered (REQUIREMENTS.md #5)

| Solution | Why not adopted |
|---|---|
| Spring Modulith events | Spring-bound — violates the no-framework requirement |
| Axon Framework | Full CQRS/ES framework + server; far from small-and-lean |
| Eventuate Tram | Framework-coupled (Spring/Micronaut), heavy infrastructure |
| Debezium outbox pattern | Requires CDC infrastructure (Kafka Connect); transport-locked |
| Greenwich/MicroProfile events | Framework/runtime-bound (Jakarta/Quarkus) |

No existing framework-free, broker-agnostic, lean transactional-outbox + domain-event library was found — hence built minimal, with SPIs where others would impose dependencies.

## Locked decisions

| # | Decision |
|---|---|
| 1 | Immutable `EventEnvelope` carries id/type/timestamp/metadata — events are never mutated (no `setDomainEventId`) |
| 2 | Event IDs are UUIDv7 strings (time-sortable), behind an `IdGenerator` SPI; no DB sequences |
| 3 | Full retry/error handling: timed retry windows (20s within first 5 min, then 5 min, suspend after ~1h) + manual suspension. Driven from the outbox store, not a broker error queue → works on any transport |
| 4 | v1 scope: `domain-event` (core) + `domain-event-jdbc` + `domain-event-rabbitmq` |
| 5 | Sync listeners run inline in `publish()`, inside the caller's transaction, before the outbox append |

## Modules

| Module | External deps | Content |
|---|---|---|
| `domain-event` | none | model, APIs, dispatch engine, outbox relay, retry policy, in-memory impls |
| `domain-event-jdbc` | none (`java.sql`) | `OutboxStore` over JDBC, PostgreSQL dialect first, `FOR UPDATE SKIP LOCKED` |
| `domain-event-rabbitmq` | `com.rabbitmq:amqp-client` | `EventTransport` for RabbitMQ + dead-letter queue for poison messages |

## Core model

```
DomainEvent                  user marker interface; eventType() default = simple class name,
                             sequenceKey() default = null (no ordering constraint)
EventEnvelope<E>             id, type, occurredAt, metadata: Map<String,String>, event
DomainEventPublisher         publish(event)
DomainEventListener<E>       fun interface: onEvent(envelope)  — async (via transport)
SyncDomainEventListener<E>   fun interface: onEvent(envelope)  — inline, in publisher's TX
```

`metadata` is an open string map rather than a fixed context type (acting user,
tenant, trace, …); populated by a `MetadataProvider` SPI.

## SPIs (all implementable without any framework)

```
EventSerializer       serialize(event): ByteArray / deserialize(type, bytes): DomainEvent
                      — users wire Jackson/kotlinx in ~10 lines; no bundled JSON dep
OutboxStore           append / claim / delete / markError / markSent / findDue / suspend
EventTransport        send(message) / startConsuming(handler) / stop
TransactionalRunner   run(action) — host adapts to Spring TX, plain JDBC, etc.
IdGenerator           default: UUIDv7 (JDK SecureRandom implementation, no dep)
MetadataProvider      current(): Map<String,String> — default empty
EventObserver         published/delivered/failed hooks — replaces Micrometer tracing coupling
RetryPolicy           nextAttemptAt(attempts, createdAt) / giveUpAfter — default = the windows above
```

## Event lifecycle (transactional outbox)

```
publish(event) [caller's TX]
  ├─ sync listeners run inline (same TX — rollback rolls everything back)
  └─ envelope appended to OutboxStore: status CREATED

OutboxRelay (JDK ScheduledExecutorService, no @Scheduled)
  └─ polls CREATED + retry-due ERROR rows → transport.send() → status SENT

Consumer engine (transport push)
  ├─ claim row (FOR UPDATE SKIP LOCKED): status PROCESSING
  ├─ sequenceKey != null → per-key lock (store-provided; PG advisory lock / in-memory map)
  ├─ async listeners in registration order, each inside TransactionalRunner.run
  ├─ success → row deleted (transient table, NOT event sourcing)
  └─ failure → status ERROR, attempts++, last_error recorded → relay retries per RetryPolicy
       └─ window exceeded → suspended = true (manual resume via store)
```

Statuses: `CREATED → SENT → PROCESSING → (deleted) | ERROR → … → suspended`.

## Table schema

Ships via the migrations convention: `domain-event-jdbc` carries a manifest +
Liquibase changelog referencing this DDL untransformed (see
[migrations-design.md](migrations-design.md)).

```sql
CREATE TABLE domain_event (
  id           VARCHAR(36)  PRIMARY KEY,
  event_type   VARCHAR(250) NOT NULL,
  payload      BYTEA        NOT NULL,
  metadata     TEXT,
  sequence_key VARCHAR(250),
  status       VARCHAR(20)  NOT NULL,
  attempts     INT          NOT NULL DEFAULT 0,
  created_at   TIMESTAMP    NOT NULL,
  modified_at  TIMESTAMP,
  suspended    BOOLEAN      NOT NULL DEFAULT FALSE,
  last_error   TEXT
);
CREATE INDEX idx_domain_event_due ON domain_event (status, created_at) WHERE NOT suspended;
```

## Design choices and the alternatives rejected

| Common alternative | nightjar | Why |
|---|---|---|
| AMQP send inside business TX + 500ms/10s repair pollers | true outbox relay | one mechanism, no ghost messages on rollback |
| Java class name on the wire + `trustedPackages` | explicit `eventType` string | wire format decoupled from packages; no deserialization trust hack |
| Spring bean scanning + typetools | explicit `subscribe(Class, listener)` | framework-free, Java-friendly, no reflection dep |
| Long IDs from DB sequence | UUIDv7 | no DB round-trip, store-agnostic |
| Micrometer tracing baked in | `EventObserver` SPI | zero deps; tracing is an adapter concern |
| Broker `.error` queue drives retry | store-driven retry | broker-agnostic (Kafka/NATS compatible) |
| A fixed context type (acting user / tenant / timestamp) auto-captured from the security context and delivered as a second listener argument | event `metadata: Map<String,String>` carried on the envelope and the wire, populated by the `MetadataProvider` SPI | zero deps, no security-framework coupling; the host decides what identity/tenant/trace to attach. Nothing populates metadata by default — define a `MetadataProvider` and read `envelope.metadata["..."]` in listeners |
| A DB-driven suspension flag checked per message — concurrency 0 pauses processing cluster-wide | `ProcessingGate` SPI checked by the relay and before each claim; the starter binds it to a worker type's suspension | same DB-driven cluster-wide stop, but optional and broker-agnostic — back it with anything |
| Full event JSON shipped in the broker message | transport message carries `eventId` + `eventType` only; the payload is re-read from the outbox on the consumer | the outbox is the source of truth either way; keeps the transport a thin notification channel. (Cross-service *integration* events would be a separate feature) |
| A bus exposing `subscribe` **and** `unsubscribe` | `subscribe`/`subscribeSync` only | listeners are wired once at assembly; no runtime churn to support |
| A minimal status set (created / processing / failed) | `CREATED`/`SENT`/`PROCESSING`/`ERROR` + `suspended`, plus `attempts`/`last_error` and a `resume(id)` API | richer inspection; the relay needs a distinct `SENT` state |

Not in v1: tracing integration, Kafka/NATS transports → later modules. (Spring adapter shipped: `nightjar-spring-boot-starter`.)

### Transaction boundaries

| Boundary | Behavior |
|---|---|
| publish | sync listeners + outbox append join the caller's transaction; a sync listener exception rolls everything back |
| processing | **one transaction** wraps every async listener of the event **and** the outbox delete — a failing listener rolls the whole set back, no partially committed listener work |
| error marking | in its own transaction, surviving the rolled-back processing transaction |
| sequence lock | held across the entire listener set; released on rollback, re-taken on retry (contention blocks rather than throwing and requeueing — same ordering, less churn) |
| retry attempt | full re-delivery: claim → lock → all listeners → delete, same boundaries |

The atomic claim (conditional `UPDATE` to `PROCESSING`) commits separately from
the processing transaction, rather than being a `SELECT … FOR UPDATE` + status
flag *inside* it — so a failed attempt is marked `ERROR` explicitly instead of
reverting to `CREATED`. Same retry outcome, and the record additionally carries
`attempts`/`last_error` for inspection.

### Cluster-wide stop (`ProcessingGate`)

`ProcessingGate` (a `fun interface`, default `OPEN`) is checked by the relay
before every poll and by consumers before claiming a message. While closed the
relay sends nothing and consumers drop messages unclaimed — **no record changes
state**, so reopening resumes delivery on the next poll; messages dropped
mid-flight are re-sent after `redeliverAfter`. This is the broker-agnostic way
to pause event processing. The Spring starter binds the gate to a worker type's
suspension (`workerPool.configure(type, 0)`), so the DB-backed concurrency
switch every instance already sees becomes the cluster stop — operationally just
`concurrency = 0` on that worker type.

## Since v1 (status)

- Schema contribution realized through the migrations convention (manifest + changelog, SQL untransformed)
- Real-service integration tests run via devbox (PostgreSQL on :6543, RabbitMQ on :5672): `./gradlew integrationTest`
- GraalVM native-image support: resource metadata shipped, no reflection anywhere, verified by `:native-smoke:nativeRun`
