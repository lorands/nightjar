# examples

Runnable, build-verified examples for the nightjar libraries. The smoke tests
execute every runnable example on each build — these examples cannot rot.

> Spring Boot apps: prefer the
> [`nightjar-spring-boot-starter`](../nightjar-spring-boot-starter/) — the
> `spring/*` wiring examples below show what it does under the hood (and how
> to wire manually without it).

| Example | Language | Shows |
|---|---|---|
| [`KotlinQuickstart`](src/main/kotlin/dev/nightjar/examples/KotlinQuickstart.kt) | Kotlin | Smallest setup: in-memory store + in-process transport, sync + async listeners |
| [`JavaQuickstart`](src/main/java/dev/nightjar/examples/JavaQuickstart.java) | Java | Records as events, lambdas as listeners, `sequenceKey`, metadata |
| [`JdbcOutboxExample`](src/main/kotlin/dev/nightjar/examples/JdbcOutboxExample.kt) | Kotlin | The transactional-outbox guarantee: commit → delivered, rollback → no ghost events |
| [`SchemaMigrationExample`](src/main/java/dev/nightjar/examples/SchemaMigrationExample.java) | Java | Manifest-discovered schema migrations with pristine SQL (the app contributes its own) |
| [`DataMigrationExample`](src/main/kotlin/dev/nightjar/examples/DataMigrationExample.kt) | Kotlin | Data-migration engine: parallel batches + strict sequential ordering |
| [`spring/DomainEventConfiguration`](src/main/java/dev/nightjar/examples/spring/DomainEventConfiguration.java) | Java | Spring Boot wiring: Spring TX integration, bean lifecycle, RabbitMQ transport |
| [`SchedulerExample`](src/main/kotlin/dev/nightjar/examples/SchedulerExample.kt) | Kotlin | Persistent aggregate timers: schedule, fire, reschedule by return value |
| [`LockAndWorkerExample`](src/main/java/dev/nightjar/examples/LockAndWorkerExample.java) | Java | Process lock with lease + worker-pool throttling and suspension |
| [`spring/DataMigrationConfiguration`](src/main/java/dev/nightjar/examples/spring/DataMigrationConfiguration.java) | Java | Spring Boot wiring for the data-migration engine |
| [`spring/CoordinationConfiguration`](src/main/java/dev/nightjar/examples/spring/CoordinationConfiguration.java) | Java | Spring Boot wiring for lock, worker pool and scheduler |
| [`quarkus/DomainEventProducers`](src/main/kotlin/dev/nightjar/examples/quarkus/DomainEventProducers.kt) | Kotlin | Quarkus wiring: pure CDI + JTA producers (any Jakarta EE 10 runtime) |
| [`quarkus/DataMigrationProducers`](src/main/kotlin/dev/nightjar/examples/quarkus/DataMigrationProducers.kt) | Kotlin | Quarkus wiring for the data-migration engine |
| [`quarkus/CoordinationProducers`](src/main/kotlin/dev/nightjar/examples/quarkus/CoordinationProducers.kt) | Kotlin | Quarkus wiring for lock, worker pool and scheduler |

Run the runnable ones:

```bash
./gradlew :examples:test        # executes them as smoke tests
# or run any main class from your IDE
```

The Spring/Quarkus wiring classes are compile-checked against `compileOnly`
framework APIs; they need a running host application to execute — copy them
into yours as a starting point.
