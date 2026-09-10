# domain-event-rabbitmq

`EventTransport` implementation over RabbitMQ for the
[domain-event](../domain-event/) library — the one optional broker binding
(NATS, Kafka, ... fit the same SPI).

**Runtime dependencies:** `com.rabbitmq:amqp-client` (the official low-level
Java client — no Spring AMQP).

## Installation

```kotlin
dependencies {
    implementation("dev.nightjar:domain-event-rabbitmq:0.1.0-SNAPSHOT")
}
```

## Usage

```kotlin
val factory = ConnectionFactory().apply { host = "rabbitmq" }
val transport = RabbitMqEventTransport(factory, "myapp.domain.events")

val bus = DomainEventBus.builder()
    .serializer(mySerializer)
    .store(myStore)
    .transport(transport)
    .build()
```

## Topology (declared idempotently on construction)

| Queue | Purpose |
|---|---|
| `<queueName>` | Durable event queue; persistent messages, manual ack after handling |
| `<queueName>.poison` | Dead-letter target for messages the consumer itself crashes on |

## Reliability model

The broker is **not** the source of truth — the outbox store is. Lost or
unacked messages are re-sent by the outbox relay, and processing failures are
retried from the store, not via broker redelivery. The `.poison` queue only
receives messages that crash the consumer machinery itself (normally: never).

Tests run against an in-memory RabbitMQ mock — `./gradlew build` needs no
broker. Real-broker integration tests (transport round-trip + full bus
pipeline) run against a devbox-provided RabbitMQ:

```bash
devbox services up
./gradlew :domain-event-rabbitmq:integrationTest
```
