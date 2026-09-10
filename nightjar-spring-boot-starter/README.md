# nightjar-spring-boot-starter

Auto-configuration for the whole nightjar surface in Spring Boot
applications. The nightjar libraries stay framework-free — everything Spring
lives here, by design (the sanctioned exception to the no-framework rule).

**Baseline: Spring Boot 3.5** · verified on **Boot 4** every build
([`spring-boot-compat-check`](../spring-boot-compat-check/)).

## Installation

```kotlin
dependencies {
    implementation("dev.nightjar:nightjar-spring-boot-starter:0.1.0-SNAPSHOT")
    // optional, when wanted:
    implementation("dev.nightjar:domain-event-rabbitmq:0.1.0-SNAPSHOT")  // transport=rabbitmq
    implementation("dev.nightjar:migrations-liquibase:0.1.0-SNAPSHOT")   // apply-on-startup
}
```

## What you write

```kotlin
@Component
class OrderShippedListener : DomainEventListener<OrderShipped> {     // auto-subscribed
    override fun onEvent(envelope: EventEnvelope<OrderShipped>) { /* idempotent */ }
}

@Service
class OrderService(private val publisher: DomainEventPublisher) {    // injectable
    @Transactional
    fun ship(id: String) {
        repository.save(...)
        publisher.publish(OrderShipped(id))   // atomic with the save — outbox
    }
}
```

Everything else is auto-configured against your `DataSource` and transaction
manager: the JDBC outbox, the transport (in-process by default, RabbitMQ via
properties), a Jackson `EventSerializer` whose allowlist is derived from your
listeners, listener subscription (`@Order` respected — concrete classes or
`@Bean` lambdas), and lifecycle (engines start after context refresh, stop
first on shutdown).

Transaction boundaries: `publish()` joins your transaction (or opens one for
bare calls) so sync listeners + outbox append are atomic with the business
change; on delivery, **one** transaction wraps all async listeners *and* the
outbox delete — a failing listener rolls the whole set back, never leaving
half-committed listener work.

The same pattern covers the rest: `DataMigrationHandlerBean` /
`ScheduledJobBean` beans are auto-registered; inject `DataMigrationEngine` /
`SimpleScheduler` / `ProcessLock` / `WorkerPool` to use them.

## Configuration

```yaml
nightjar:
  domain-event:
    transport: rabbitmq            # default: in-process
    rabbitmq: { host: rabbit, queue: myapp.domain.events }
    poll-interval: 500ms
  migrations:
    apply-on-startup: false        # init container recommended; true needs migrations-liquibase
    data.enabled: true             # data-migration engine (opt-in)
  coordination:
    scheduler.enabled: true        # simple scheduler (opt-in)
    worker.enabled: true           # worker pool bean (opt-in)
```

Full property reference: [user manual](../docs/spring-boot-starter-manual.adoc).
Every auto-configured bean is `@ConditionalOnMissingBean` — define your own
(`EventSerializer`, `OutboxStore`, `EventTransport`, …) to take over.

## Notes

- **Cluster-wide stop**: with `nightjar.coordination.worker.enabled=true`,
  `workerPool.configure("nightjar-domain-events", 0)` pauses delivery on every
  instance (and `1` resumes — nothing lost); broker-agnostic
- The auto-configured `ProcessLock` joins the active transaction — acquire
  inside `@Transactional` and a rollback frees the lock, a commit holds it
- **Identity isn't propagated automatically**: to attach the acting user,
  tenant or trace to events, define a `MetadataProvider` bean that reads your
  security context, then read `envelope.getMetadata()` in listeners. Events
  flow without it; the metadata is just absent
- Kotlin data-class events need `jackson-module-kotlin` in your `ObjectMapper`
  bean (the starter uses your mapper when present)
- Events overriding `eventType()` need a custom `EventSerializer` bean (the
  Jackson default keys by simple class name)
- If `migrations-liquibase` is on your classpath but you migrate via init
  container, set `spring.liquibase.enabled=false` (Boot's own Liquibase
  integration is a separate mechanism)
