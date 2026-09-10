# domain-event internals — a code walkthrough

A line-by-line tour of the `domain-event` module: what each type does and how
the engine works, in the order that builds understanding — the problem, the
data, the seams (SPIs), the engine, the JDBC store, the helpers.

This is the *implementation* companion to:
- [domain-event-design.md](domain-event-design.md) — rationale and the alternatives rejected
- [domain-event-manual.adoc](domain-event-manual.adoc) — the user-facing guide

File/line references point at the source as of this writing; treat them as a
map, not a contract — they drift as the code changes.

## Contents

1. [The problem it solves: the transactional outbox](#1-the-problem-it-solves-the-transactional-outbox)
2. [The data model](#2-the-data-model)
3. [The SPIs — the seams where you plug in](#3-the-spis--the-seams-where-you-plug-in)
4. [The engine: DomainEventBus](#4-the-engine-domaineventbus)
5. [The relay: OutboxRelay](#5-the-relay-outboxrelay--the-background-delivery-engine)
6. [The JDBC store: where the transaction magic happens](#6-the-jdbc-store-where-the-transaction-magic-happens)
7. [The helpers](#7-the-helpers)
8. [The whole thing in one breath](#8-the-whole-thing-in-one-breath)

---

## 1. The problem it solves: the transactional outbox

When your code does two things — change business data *and* announce "this
happened" — you have a consistency hole:

```
order saved to DB ✓
... crash ...
"OrderPlaced" message never sent ✗   → other systems never hear about the order
```

or the reverse: you send the message, then the DB transaction rolls back, and
now you've announced an order that doesn't exist (a *ghost event*).

The **transactional outbox** closes the hole: instead of sending a message, you
write a row into a `domain_event` table **in the same database transaction as
the business change**. Commit → both the order and its event-row are durable,
atomically. Roll back → both vanish. A separate background process (the
*relay*) later reads those rows and delivers them. The database is the single
source of truth; the message broker is just a notification wire that's allowed
to lose messages, because the relay will re-send.

nightjar's whole domain-event module is an implementation of that pattern,
framework-free, plus four behaviors layered on top: sync listeners, sequence
ordering, time-windowed retry and a cluster-wide stop.

---

## 2. The data model

### `DomainEvent` — the marker your events implement
`DomainEvent.kt:10-26`

```kotlin
public interface DomainEvent {
    public fun eventType(): String = javaClass.simpleName   // line 18
    public fun sequenceKey(): String? = null                // line 25
}
```

- **Line 18** — `eventType()` is the *stable string* that goes on the wire and
  into the DB, decoupled from the Java package/class. Default is the simple
  class name (`OrderPlaced`); override it so renaming the class doesn't break
  already-persisted events. This is deliberately a string and not the FQN —
  shipping the Java class name would mean trusting a class name off the wire
  (and needing a package allowlist to do it safely); nightjar never does.
- **Line 25** — `sequenceKey()`: events sharing a non-null key are processed
  **one at a time, in publish order** (e.g. all events for `order-42`). `null`
  (default) = process concurrently. This is the hook the sequence lock uses
  later.

It's a behavior-only interface — no id, no timestamp. Events stay immutable
domain values. The infrastructure data lives in the envelope.

### `EventEnvelope<E>` — the event plus its infrastructure identity
`EventEnvelope.kt:11-30`

```kotlin
public class EventEnvelope<E : DomainEvent>(
    public val id: String,                      // UUIDv7
    public val type: String,                    // eventType()
    public val occurredAt: Instant,
    public val metadata: Map<String, String>,   // user/tenant/trace captured at publish
    public val event: E,
)
```

- **Lines 24-25** — equality is **by `id` only**. Two envelopes with the same
  id are "the same event" regardless of payload. That matters because the id is
  the dedup/claim key across the cluster.
- `metadata` (line 19) is an open map rather than a fixed context struct, so
  the host decides what identity/tenant/trace to attach. Populated by the
  `MetadataProvider` SPI.

### `OutboxRecord` + `OutboxStatus` — one row in the outbox
`OutboxRecord.kt`

```kotlin
public enum class OutboxStatus { CREATED, SENT, PROCESSING, ERROR }   // lines 6-18
```

The lifecycle of a record:

```
CREATED ── relay sends ──▶ SENT ── consumer claims ──▶ PROCESSING ──▶ (deleted on success)
   ▲                                                        │
   └──────────── resume() ◀── suspended=true ◀── ERROR ◀────┘ (listener failed)
```

- **`CREATED`** — written in the publishing transaction, not yet sent.
- **`SENT`** — handed to the transport.
- **`PROCESSING`** — a consumer has atomically claimed it.
- **`ERROR`** — a listener threw; eligible for retry.
- Success is *absence* — the record is **deleted**, not kept (the outbox is
  "transient reliability storage, not an event store", `OutboxRecord.kt:23-24`).

The record carries `attempts`, `createdAt`, `modifiedAt`, `suspended`,
`lastError` (`OutboxRecord.kt:33-41`) — everything the relay's retry logic
needs, plus `lastError` (the stack trace) for human inspection.

---

## 3. The SPIs — the seams where you plug in

nightjar ships *no* JSON library, *no* broker client, *no* transaction manager
in core. Each is a small interface (an SPI). The engine depends only on these:

| SPI | Method(s) | What you adapt |
|---|---|---|
| `EventSerializer` | `serialize`/`deserialize` | `EventSerializer.kt:15-18` — Jackson, kotlinx, etc. `deserialize` maps the `type` string back to a class and **throws on unknown types** (never deserialize arbitrary class names) |
| `OutboxStore` | append/claim/delete/markError/… | the DB persistence (JDBC, in-memory) |
| `EventTransport` | `send`/`startConsuming` | the broker (RabbitMQ, in-process) |
| `TransactionalRunner` | `run(Runnable)` | wraps work in *a* transaction |
| `TransactionalConnectionSource` | `current(): Connection?` | hands the store the active TX's connection |
| `IdGenerator` | `nextId()` | `IdGenerator.kt` — default UUIDv7 |
| `MetadataProvider` | `current()` | ambient context at publish |
| `RetryPolicy` | `isRetryDue`/`isExhausted` | backoff strategy |
| `EventObserver` | `published`/`processed`/`failed`/`suspended` | metrics/tracing; all no-op by default (`EventObserver.kt`) |
| `ProcessingGate` | `isOpen()` | cluster-wide stop switch |

The two transaction SPIs are the clever part — they're how a framework-free
library gets atomicity. `TransactionalConnectionSource.current()` returns the
JDBC `Connection` bound to the caller's active transaction (or `null` if there
isn't one). The store uses *that* connection so its INSERT joins the caller's
transaction. `TransactionalRunner.run{}` opens a transaction around a block. In
Spring these are backed by `DataSourceUtils` and `TransactionTemplate`;
framework-free, by `JdbcTransactions` (a `ThreadLocal<Connection>`).

---

## 4. The engine: `DomainEventBus`

This is the heart. It's three responsibilities in one class: **publish** (write
side), **consume** (read side), and **lifecycle**. It holds two listener
registries and delegates background relaying to `OutboxRelay`.

### Construction & fields
`DomainEventBus.kt:42-59`

```kotlin
private val serializer = requireNotNull(builder.serializer) { "serializer is required" }   // 42
private val store = requireNotNull(builder.store) ...                                        // 43
private val transport = requireNotNull(builder.transport) ...                               // 44
private val transactionalRunner = builder.transactionalRunner                               // 45
...
private val processingGate = builder.processingGate                                         // 50

private val relay = OutboxRelay(store, transport, builder.retryPolicy, clock, observer,
    builder.pollInterval, builder.batchSize, builder.redeliverAfter, processingGate)         // 51-54

private val syncListeners  = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<Registration>>()  // 56
private val asyncListeners = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<Registration>>()  // 57
private val registrationSeq = AtomicInteger()                                                    // 58
private val started = AtomicBoolean()                                                            // 59
```

- **42-44** — the three required collaborators; `requireNotNull` gives a clear
  failure if the builder is misused.
- **45-50** — everything else has a default (`TransactionalRunner.DIRECT`,
  `ProcessingGate.OPEN`, `MetadataProvider.EMPTY`, …), so the minimal bus needs
  only serializer+store+transport.
- **56-57** — two registries, keyed by the event's `Class`. `ConcurrentHashMap`
  because listeners can be registered from any thread; `CopyOnWriteArrayList`
  because the lists are read on every dispatch (hot path) and written rarely.
- **58** — a monotonic counter that gives every registration a tiebreak
  sequence (so two listeners with the same `@Order` keep registration order —
  stable, deterministic).

### Subscription
`DomainEventBus.kt:85-94`

```kotlin
private fun register(registry, eventClass, order, listener) {
    val list = registry.computeIfAbsent(eventClass) { CopyOnWriteArrayList() }   // 91
    list.add(Registration(order, registrationSeq.getAndIncrement(), listener))  // 92
    list.sortWith(compareBy({ it.order }, { it.seq }))                          // 93
}
```

- **91** — first listener for an event type creates its list.
- **92** — wrap the listener with its `order` and a unique `seq`.
- **93** — keep the list sorted by `(order, seq)`. Sorting on insert (not on
  dispatch) means the hot path just iterates. Lower `order` runs first; ties
  run in registration order.

### The publish path — the write side
`DomainEventBus.kt:98-126`

```kotlin
override fun publish(event: DomainEvent) {
    val envelope = EventEnvelope(
        idGenerator.nextId(), event.eventType(), clock.instant(),
        metadataProvider.current(), event,                              // 99-101
    )

    // sync listeners run inline, in the caller's transaction
    dispatch(syncListeners, envelope) { registration, env ->            // 105
        (registration.listener as SyncDomainEventListener<DomainEvent>).onEvent(env)  // 107
    }

    store.append(OutboxRecord(
        id = envelope.id,
        eventType = envelope.type,
        payload = serializer.serialize(event),                         // 114
        metadata = envelope.metadata,
        sequenceKey = event.sequenceKey(),                             // 116
        status = OutboxStatus.CREATED,                                 // 117
        attempts = 0, createdAt = envelope.occurredAt,
        modifiedAt = null, suspended = false, lastError = null,
    ))                                                                  // 110-124
    observer.published(envelope)                                       // 125
}
```

Read this as three things that all happen **on the caller's thread, inside the
caller's transaction**:

1. **99-101** — build the envelope: generate the UUIDv7 id, snapshot the
   timestamp, capture ambient metadata. The event itself is untouched.
2. **105-108** — run **sync listeners inline, right now**. Key consequence: if
   a sync listener throws (a domain-rule veto), the exception propagates out of
   `publish`, and since we're inside the caller's transaction, **the whole
   thing rolls back — including the order you were saving**. This is the
   "synchronous validation" hook. The order (sync before append) doesn't matter
   for atomicity — they're all in one transaction.
3. **110-124** — `store.append(...)`. The record is built with
   `status = CREATED`. Critically, `JdbcOutboxStore.append` asks
   `TransactionalConnectionSource.current()` and inserts on the caller's
   connection — so **this INSERT commits or rolls back with the business
   data**. `serializer.serialize(event)` (line 114) turns the event into bytes
   now, while we still have the live object.
4. **125** — fire the observability hook (tracing/metrics). No-op by default.

After `publish` returns and the caller's transaction commits, the record sits
in the DB as `CREATED`. The publisher's job is done. Nothing has been sent yet
— that's the relay's job, decoupled in time.

### Lifecycle
`DomainEventBus.kt:130-142`

```kotlin
public fun start() {
    check(!started.getAndSet(true)) { "DomainEventBus already started" }   // 132
    transport.startConsuming { message -> handleMessage(message) }         // 133
    relay.start()                                                          // 134
}
```

- **132** — `getAndSet(true)` atomically flips the flag and returns the old
  value; if it was already `true`, `check` throws. Idempotency guard: you can't
  start twice.
- **133** — tell the transport to push incoming messages into `handleMessage`.
  This is the consumer side waking up.
- **134** — start the background relay thread (the poller). `close()` (137-142)
  stops both in reverse.

### The consume path — the read side
`DomainEventBus.kt:146-204`

When a message arrives from the transport (could be from *this* instance's
relay, or another instance in the cluster):

```kotlin
private fun handleMessage(message: TransportMessage) {
    try {
        if (!processingGate.isOpen()) {                  // 150
            log.log(DEBUG, "... dropping ...")
            return                                       // 153
        }
        val record = store.claim(message.eventId) ?: return   // 156
        process(record)                                       // 157
    } catch (e: Exception) {
        log.log(ERROR, "...", e)                              // 159
    }
}
```

- **150-153** — the **cluster-wide stop**. If the gate is closed, drop the
  message and return. Nothing is claimed, nothing changes state — the record
  stays `SENT` in the DB and the relay will re-send it later once the gate
  reopens. This is the broker-agnostic way to pause the event worker.
- **156** — `store.claim(eventId)` is the **atomic claim**: it tries to
  transition the record to `PROCESSING` and return it. Returns `null` if the
  record is gone (already processed by someone), suspended, or *another
  instance claimed it first*. In a cluster, the same message may be delivered
  to multiple instances; exactly one wins the claim, the rest get `null` and
  silently drop. The `?: return` is the whole dedup story.
- **159** — `handleMessage` never throws; a failure here just logs. The
  transport must not be derailed by one bad message.

Then `process`:

```kotlin
private fun process(record: OutboxRecord) {
    val envelope = try {
        val event = serializer.deserialize(record.eventType, record.payload)   // 165
        EventEnvelope(record.id, record.eventType, record.createdAt, record.metadata, event)
    } catch (e: Exception) {
        store.markError(record.id, e.stackTraceToString())    // 168
        log.log(ERROR, "Failed to deserialize ...", e)
        return                                                // 170
    }

    try {
        val processing = Runnable {                           // 179
            transactionalRunner.run {                         // 180
                dispatchAsync(envelope)                       // 181
                store.delete(record.id)                       // 182
            }
        }
        val sequenceKey = record.sequenceKey
        if (sequenceKey != null) {
            store.withSequenceLock(sequenceKey, processing)   // 187
        } else {
            processing.run()                                  // 189
        }
        observer.processed(envelope)                          // 191
    } catch (e: Exception) {
        store.markError(record.id, e.stackTraceToString())    // 193
        observer.failed(envelope, e)
        log.log(WARNING, "... failed; will retry", e)
    }
}
```

This is the most important method in the module. Two phases:

**Deserialize (164-171), outside any transaction.** Turn the stored bytes back
into an event object. If deserialization fails — e.g. the event type was
removed from the serializer's allowlist — there's no point retrying with
listeners, so mark `ERROR` and return. (It'll still be retried by the relay
later, in case it's a transient/classpath issue, but eventually suspended.)

**Process (173-196), the one-transaction core.** The *nesting order* is the
whole design:

```
withSequenceLock(key) {           ← outermost: cluster-wide ordering lock (DB row lock)
    transactionalRunner.run {     ← ONE transaction
        dispatchAsync(envelope)   ← ALL async listeners, in @Order
        store.delete(record.id)   ← the delete, same transaction
    }
}
```

- **180-183** — **one transaction wraps every async listener *and* the
  delete**. If listener #2 throws, listener #1's DB work rolls back *and* the
  delete never commits — so the record survives and the whole set re-runs on
  retry. No half-applied state. `store.delete` joins this transaction via the
  same `TransactionalConnectionSource` mechanism as `append`, so the record
  disappears atomically with the listeners' committed effects.
- **186-189** — if the event has a `sequenceKey`, the whole transaction runs
  **inside `withSequenceLock`**, a cluster-wide mutex (a `SELECT … FOR UPDATE`
  on a lock-table row). That serializes all events for, say, `order-42` across
  every instance, preserving publish order. No key → run directly, full
  concurrency.
- **192-196** — if *anything* in that block throws, the transaction has already
  rolled back; we now mark the record `ERROR` **in its own fresh transaction**
  (193) — that's why `markError` uses an autonomous connection, so it survives
  the rollback. The relay will pick it up for retry.

`dispatchAsync` (199-204) just iterates the async registry and calls each
listener's `onEvent` — no transaction management of its own; the transaction is
the caller's (`process`).

### The shared dispatch helper
`DomainEventBus.kt:206-218`

```kotlin
private fun dispatch(registry, envelope, invoke) {
    val listeners = registry[envelope.event.javaClass]            // 211
    if (listeners.isNullOrEmpty()) {
        if (registry === asyncListeners)
            log.log(WARNING, "No listeners ... dropped")
        return
    }
    for (registration in listeners) invoke(registration, envelope)
}
```

- **211** — lookup is by the event's **concrete runtime class**. (This is why
  SAM-lambda listeners needed special generic-resolution handling in the Spring
  starter — the registry key must be the real event class.)
- It iterates the already-sorted list and applies the `invoke` lambda, which
  differs for sync (call directly) vs async (called inside the transaction by
  `dispatchAsync`). A warning is logged if an async event has no listeners (a
  likely misconfiguration); sync having none is normal.

---

## 5. The relay: `OutboxRelay` — the background delivery engine

`OutboxRelay.kt`. One daemon thread that polls the store on a fixed cadence and
decides, per record, what to do.

```kotlin
private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "nightjar-outbox-relay").apply { isDaemon = true }   // 33-34
}

fun start() {
    scheduler.scheduleWithFixedDelay(::safePoll, pollInterval, pollInterval, MILLISECONDS)  // 38-40
}
```

- **33-34** — a single daemon thread named for easy thread-dump reading.
  Single-threaded because ordering and simplicity matter more than throughput
  here; the work is just DB reads + transport sends.
- **38-40** — `scheduleWithFixedDelay`: the *delay* is between the end of one
  poll and the start of the next (not fixed-rate), so a slow poll can't pile up
  overlapping runs.

```kotlin
internal fun pollOnce() {
    if (!processingGate.isOpen()) return                  // 55-57 — cluster-wide stop
    val now = clock.instant()
    for (record in store.findPending(batchSize)) {        // 54
        try { relay(record, now) }
        catch (e: Exception) { log.log(ERROR, "...", e) } // 57-59 — one bad record doesn't kill the batch
    }
}
```

- **55-57** — gate closed → send nothing this pass. Records keep their state;
  next open pass delivers them.
- **`findPending`** returns records that are `CREATED`, `ERROR`, or `SENT`, not
  suspended, oldest first — candidates the relay then judges individually.
- Per-record try/catch so one failure doesn't abort the rest of the batch.

The decision table, `relay()`:
`OutboxRelay.kt:63-85`

```kotlin
when (record.status) {
    CREATED -> send(record)                               // 65 — first delivery

    SENT -> {                                             // 69 — was sent, never processed?
        val sentAt = record.modifiedAt ?: record.createdAt
        if (Duration.between(sentAt, now) >= redeliverAfter) send(record)   // 71
    }

    ERROR ->                                              // 74 — failed processing
        if (retryPolicy.isExhausted(record.createdAt, now)) {
            store.markSuspended(record.id)                // 76
            observer.suspended(record.id, record.eventType)
        } else if (retryPolicy.isRetryDue(record.createdAt, record.modifiedAt ?: record.createdAt, now)) {
            send(record)                                  // 80
        }

    PROCESSING -> {}                                      // 83 — someone's on it; leave alone
}
```

- **CREATED (65)** — the normal case: send it.
- **SENT (69-71)** — it was sent but is *still here* (not deleted), meaning no
  consumer ever finished it — a lost message (broker restart, consumer crash
  before claim). After `redeliverAfter` (default 1h) the relay **re-sends** it.
  This is the recovery mechanism that makes "transport may lose messages"
  acceptable.
- **ERROR (74-81)** — a listener failed. Ask the `RetryPolicy`: if the total
  window is exhausted, **suspend** it (stop retrying, keep it for inspection);
  otherwise, if a retry is due, **re-send** it (which re-claims, re-runs all
  listeners).
- **PROCESSING (83)** — explicitly do nothing; a consumer holds it. (Stores
  shouldn't even return these, but the branch documents intent.)

`send()`:
`OutboxRelay.kt:87-90`

```kotlin
private fun send(record: OutboxRecord) {
    transport.send(TransportMessage(record.id, record.eventType, record.payload, record.metadata))  // 88
    store.markSent(record.id)                                                                        // 89
}
```

Send to the broker, then flip to `SENT`. The message carries id + type +
payload + metadata, but the consumer re-reads the authoritative record from the
DB via `claim` — the transport message is just a notification.

---

## 6. The JDBC store: where the transaction magic happens

`JdbcOutboxStore.kt`. The two "joins the transaction" operations vs the
"autonomous" ones is the crux.

**`append` — joins the caller's transaction** (`JdbcOutboxStore.kt:38-45`):

```kotlin
override fun append(record: OutboxRecord) {
    val transactional = transactionalConnections.current()       // 39
    if (transactional != null) {
        insert(transactional, record)   // joins caller's TX; not ours to commit/close   // 41
    } else {
        dataSource.connection.use { insert(it, record) }          // 43 — autonomous
    }
}
```

- **39** — ask: is there an active transaction? `current()` returns its
  `Connection` or `null`.
- **41** — if yes, INSERT on *that* connection. We don't commit or close it —
  the caller (Spring/`JdbcTransactions`) owns its lifecycle. This is how the
  outbox row becomes atomic with the business data.
- **43** — if no transaction, use a short autocommit connection. (Less safe —
  used in tests/demos.)

`delete` has the identical structure — it joins the *processing* transaction so
the record vanishes with the listeners' work.

**`claim` — atomic, autonomous** (`JdbcOutboxStore.kt:89-110`):

```kotlin
dataSource.connection.use { connection ->
    connection.autoCommit = false                               // 91
    val claimed = connection.prepareStatement(
        "UPDATE $tableName SET status = 'PROCESSING', modified_at = ? " +
        "WHERE id = ? AND status <> 'PROCESSING' AND suspended = FALSE"   // 96-97
    ).use { ... executeUpdate() == 1 }                          // 101
    val record = if (claimed) selectById(connection, id) else null   // 103
    connection.commit()                                          // 104
    record
}
```

- **96-97** — the claim is a *conditional* UPDATE: set to `PROCESSING` only if
  not already `PROCESSING` and not suspended. The database guarantees exactly
  one of N racing instances gets `updateCount == 1` (101); the losers get 0 →
  `null`. No application-level locking needed — the row update is the mutex.
  This replaces the usual `SELECT … FOR UPDATE` + status flag.
- This runs in its *own* short transaction (91/104), separate from the later
  processing transaction — so a claim that succeeds, then a processing failure,
  leaves the row as `ERROR` (marked explicitly), not reverted to `CREATED`.

**`withSequenceLock` — the cross-instance ordering mutex**
(`JdbcOutboxStore.kt:142-161`):

```kotlin
dataSource.connection.use { connection ->
    connection.autoCommit = false
    ensureLockRow(connection, key)                              // 146 — make sure a row exists to lock
    connection.prepareStatement("SELECT lock_key FROM $lockTableName WHERE lock_key = ? FOR UPDATE")  // 149
        .use { ... }                                            // acquire the row lock — blocks if held
    action.run()                                                // 154 — the whole processing TX
    connection.commit()                                         // 155 — releases the lock
}
```

- **149** — `SELECT … FOR UPDATE` on a dedicated lock-table row. Any other
  instance trying the same key **blocks** here until we commit. That's the
  cluster-wide serialization for one `sequenceKey`.
- **154** — the guarded action (the entire listeners+delete transaction) runs
  while the lock is held.
- **155/157** — commit (or rollback on exception) releases the lock. nightjar
  *blocks* on contention rather than throwing and requeueing — same ordering
  guarantee, less broker churn.

---

## 7. The helpers

**`UuidV7Generator`** (`UuidV7Generator.kt:19-36`) — builds an RFC 9562 UUIDv7
by hand: 48-bit millisecond timestamp in the high bits (line 24), version
nibble `0x7` (25), variant `10` (29), random fill for the rest. Result:
globally unique *and* time-sortable, so outbox rows and log ids order naturally
with no DB sequence round-trip. Uses an instance `SecureRandom` (not static — a
native-image constraint).

**`TimeWindowRetryPolicy`** (`TimeWindowRetryPolicy.kt`) — the default
backoff: `isRetryDue` picks `youngInterval` (20s) if the record is younger than
`youngWindow` (5min), else `matureInterval` (5min); `isExhausted` is true past
`maxAge` (1h). Pure timestamp arithmetic, no state — the timestamps live on the
record.

**`InMemoryOutboxStore`** (`InMemoryOutboxStore.kt`) — a `ConcurrentHashMap`
mirror of the JDBC store for tests/single-instance. `claim` uses
`computeIfPresent` for atomicity (38-48); `withSequenceLock` uses an in-process
`ReentrantLock` per key (68-70). Explicitly *not* transactional (line 13-14) —
appends are immediately visible regardless of caller transaction, which is why
it's test-only.

**`InProcessEventTransport`** (`InProcessEventTransport.kt`) — a single daemon
thread delivering messages in send order, with a `beforeStartBuffer` (22) so
messages sent before `startConsuming` aren't lost. Lets the full async path run
with no broker.

---

## 8. The whole thing in one breath

`publish()` writes an event-row in your transaction (atomic with your data,
sync listeners can veto). A background **relay** thread sees `CREATED` rows and
**sends** them to a transport (marking `SENT`), re-sending anything lost after a
timeout. The transport pushes to a **consumer** that **atomically claims** the
row (one cluster instance wins), runs **all async listeners + the delete in one
transaction** (optionally serialized by `sequenceKey`), and on failure marks
`ERROR` for the relay to **retry** on a time-window schedule until success
(deleted) or exhaustion (suspended). A **ProcessingGate** can pause the whole
cluster; every persistence and transaction concern is an **SPI** you adapt in a
few lines.
