# nightjar

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/lorands/nightjar?sort=semver)](https://github.com/lorands/nightjar/releases)

Minimalist, framework-free DDD building blocks for the JVM.

- **Java 17+**, Kotlin-first with first-class Java APIs (records as events, lambdas as listeners)
- **Zero framework dependencies** — core modules depend on `kotlin-stdlib` only; integrations are thin SPIs you adapt in a few lines
- **Broker- and database-agnostic** — abstractions in core, one optional binding per technology as a separate module
- **GraalVM native-image ready** — no reflection/scanning, resource metadata shipped, verified by an AOT-compiled smoke harness ([`native-smoke`](native-smoke/))
- Hard design rules live in [REQUIREMENTS.md](REQUIREMENTS.md)

## Modules

| Module | Purpose | Runtime dependencies |
|---|---|---|
| [`coordination`](coordination/) | Cluster primitives: process lock (leased mutex), worker pool (throttle), simple scheduler (persistent timers) | none |
| [`coordination-jdbc`](coordination-jdbc/) | Database-backed lock/worker/scheduler | none (`java.sql`) |
| [`domain-event`](domain-event/) | Domain events with a transactional outbox: sync/async listeners, retries, suspension | none |
| [`domain-event-jdbc`](domain-event-jdbc/) | Outbox persistence over plain JDBC | none (`java.sql`) |
| [`domain-event-rabbitmq`](domain-event-rabbitmq/) | RabbitMQ event transport | `com.rabbitmq:amqp-client` |
| [`migrations`](migrations/) | Schema-migration convention (manifests + pristine SQL) and the data-migration engine | none |
| [`migrations-jdbc`](migrations-jdbc/) | Data-migration queues over plain JDBC | none (`java.sql`) |
| [`migrations-liquibase`](migrations-liquibase/) | Schema execution via Liquibase — deploy-time only (init container/CLI) | `liquibase-core` (deploy time) |
| [`nightjar-spring-boot-starter`](nightjar-spring-boot-starter/) | Auto-configuration for everything above (Boot 3.5 baseline, Boot 4 verified) | Spring Boot |
| [`examples`](examples/) | Runnable Java & Kotlin examples, Spring Boot & Quarkus wiring | — (not published) |
| [`native-smoke`](native-smoke/) | GraalVM native-image verification harness | — (not published) |
| [`spring-boot-compat-check`](spring-boot-compat-check/) | Boot 4 compatibility verification for the starter | — (not published) |

## Quick taste

```kotlin
val bus = DomainEventBus.builder()
    .serializer(MyJsonSerializer)            // adapt Jackson/kotlinx in ~10 lines
    .store(JdbcOutboxStore(dataSource, tx))  // or InMemoryOutboxStore() for tests
    .transport(RabbitMqEventTransport(factory, "myapp.domain.events"))
    .build()

bus.subscribe(OrderPlaced::class.java) { envelope -> /* idempotent side effect */ }
bus.start()

transactions.run {
    repository.save(order)
    bus.publish(OrderPlaced(order.id))   // commits or rolls back WITH the order
}
```

Java looks just like you'd hope — see [examples](examples/).

## Installation

Not on Maven Central yet. Every tagged release publishes jars (plus sources
jars, POMs and checksums) to
[GitHub Releases](https://github.com/lorands/nightjar/releases) — including
`nightjar-<version>-maven-repo.zip`, a ready-made Maven repository. Unpack it
and point a repository at it:

```kotlin
repositories {
    maven { url = uri("file:///path/to/unpacked-maven-repo") }
}

dependencies {
    implementation("dev.nightjar:domain-event:<version>")
    implementation("dev.nightjar:domain-event-jdbc:<version>")
    // or, on Spring Boot, the whole surface at once:
    implementation("dev.nightjar:nightjar-spring-boot-starter:<version>")
}
```

Or build from source and install into your local Maven repository:

```bash
git clone https://github.com/lorands/nightjar.git && cd nightjar
./gradlew build              # verifies everything hermetically
./gradlew publishToMavenLocal # installs dev.nightjar:* into ~/.m2
```

Requirements to build: JDK 17+ (the Gradle daemon provisions what it needs).

## Development

`./gradlew build` is fully hermetic (H2 + in-memory + broker mock). Tests
against real PostgreSQL and RabbitMQ run separately via
[devbox](https://www.jetify.com/devbox) (Nix-based — no Docker daemon needed,
CI-friendly):

```bash
devbox services up          # PostgreSQL on :6543, RabbitMQ on :5672
./gradlew integrationTest   # skips gracefully when services are down

./gradlew :native-smoke:nativeRun   # GraalVM AOT smoke test (toolchain auto-provisioned)
```

## Documentation

- [domain-event user manual](docs/domain-event-manual.adoc) · [design](docs/domain-event-design.md) · [internals walkthrough](docs/domain-event-internals.md)
- [migrations user manual](docs/migrations-manual.adoc) · [design](docs/migrations-design.md)
- [coordination user manual](docs/coordination-manual.adoc) · [design](docs/coordination-design.md)
- [Spring Boot starter manual](docs/spring-boot-starter-manual.adoc) · [design](docs/spring-boot-starter-design.md)
- Per-module READMEs for installation and usage

## Releasing

Releases are tag-driven. Pushing a semver tag runs
[`.github/workflows/release.yml`](.github/workflows/release.yml), which builds,
runs the hermetic test suite, and publishes a GitHub release with every
published module's jar and sources jar attached:

```bash
git tag v1.2.3 && git push origin v1.2.3
```

The version comes from the tag — nothing in the repository records it, and
ordinary builds stay on `0.1.0-SNAPSHOT`. Tags with a pre-release identifier
(`v1.2.3-rc.1`) are marked as pre-releases. The workflow's *Run workflow*
button does a dry run: it builds and uploads the artifacts without creating a
release.

Modules applying the `nightjar.published` convention plugin are the ones that
ship; `examples`, `native-smoke` and `spring-boot-compat-check` deliberately
do not.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
