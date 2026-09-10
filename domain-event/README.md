# domain-event

Framework-free domain events for DDD applications, built around a transactional
outbox: events publish atomically with your business data and are delivered
at-least-once to asynchronous listeners, with timed retries and suspension.

**Runtime dependencies: none** (`kotlin-stdlib` only).

## Installation

```kotlin
dependencies {
    implementation("com.lorands.nightjar:domain-event:<version>")
    // pick a store + transport, or use the bundled in-memory ones:
    implementation("com.lorands.nightjar:domain-event-jdbc:<version>")
    implementation("com.lorands.nightjar:domain-event-rabbitmq:<version>")
}
```

(Not yet on Maven Central — build from source, see the [root README](../README.md).)

## Usage

### Kotlin

```kotlin
class OrderPlaced(val orderId: String) : DomainEvent

val bus = DomainEventBus.builder()
    .serializer(MySerializer)               // EventSerializer SPI — bring your JSON library
    .store(InMemoryOutboxStore())           // or JdbcOutboxStore
    .transport(InProcessEventTransport())   // or RabbitMqEventTransport
    .build()

bus.subscribeSync(OrderPlaced::class.java) { env -> /* inline, in the publishing transaction */ }
bus.subscribe(OrderPlaced::class.java) { env -> /* async, at-least-once — must be idempotent */ }

bus.start()
bus.publish(OrderPlaced("order-1"))
```

### Java

```java
public record OrderShipped(String orderId) implements DomainEvent {
    @Override public String sequenceKey() { return orderId(); } // per-order ordering
}

DomainEventBus bus = DomainEventBus.builder()
        .serializer(SERIALIZER)
        .store(new InMemoryOutboxStore())
        .transport(new InProcessEventTransport())
        .build();

bus.subscribe(OrderShipped.class, env -> handle(env.getEvent()));
bus.start();
```

Full, runnable versions: [`examples/`](../examples/). Complete documentation:
[user manual](../docs/domain-event-manual.adoc).

## Key concepts

- **`EventEnvelope`** carries id (UUIDv7), type, timestamp and metadata — events stay immutable domain values
- **Sync listeners** run inside the publishing transaction; their exceptions roll it back
- **Async listeners** are delivered through the outbox: all listeners of one event plus the outbox delete run in a single transaction (a failing listener rolls the whole set back), failed processing retries (default: every 20s for 5 min, then every 5 min, suspended after 1 h), `store.resume(id)` redelivers suspended events
- **`sequenceKey`** serializes processing of related events across all application instances
- **`ProcessingGate`** is a cluster-wide stop: while closed the relay sends nothing and consumers drop messages unclaimed — wire it to the worker pool's `isSuspended(type)` for database-driven pause/resume
- All integration points are small SPIs: `OutboxStore`, `EventTransport`, `EventSerializer`, `TransactionalRunner`, `IdGenerator`, `MetadataProvider`, `EventObserver`, `RetryPolicy`, `ProcessingGate`
