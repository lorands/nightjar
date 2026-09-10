# nightjar

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/com.lorands.nightjar/domain-event.svg?label=maven%20central)](https://central.sonatype.com/search?namespace=com.lorands.nightjar)
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

Artifacts are published to **Maven Central** under the `com.lorands.nightjar`
group id — no credentials, no extra repository:

```kotlin
dependencies {
    implementation("com.lorands.nightjar:domain-event:<version>")
    implementation("com.lorands.nightjar:domain-event-jdbc:<version>")
    // or, on Spring Boot, the whole surface at once:
    implementation("com.lorands.nightjar:nightjar-spring-boot-starter:<version>")
}
```

```xml
<dependency>
  <groupId>com.lorands.nightjar</groupId>
  <artifactId>domain-event</artifactId>
  <version><!-- version --></version>
</dependency>
```

Every release also goes to
[GitHub Packages](docs/installation-manual.adoc#_github_packages) and attaches
its jars to the
[GitHub release](https://github.com/lorands/nightjar/releases). Module
coordinates, Groovy DSL, version catalogs, CI recipes and troubleshooting are
in the **[installation manual](docs/installation-manual.adoc)**.

> **Coordinates changed in the first Maven Central release.** Versions up to
> 0.1.2 were published only to GitHub Packages, under the old `dev.nightjar`
> group id. Kotlin packages remain `dev.nightjar.*` and are unaffected — a
> package name and a Maven group id are independent, and renaming 105 files
> would break every import for no benefit.

### From source

```bash
git clone https://github.com/lorands/nightjar.git && cd nightjar
./gradlew build               # verifies everything hermetically
./gradlew publishToMavenLocal # installs com.lorands.nightjar:* into ~/.m2
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
- [Installation manual](docs/installation-manual.adoc) — consuming the jars from Gradle and Maven
- Per-module READMEs for installation and usage

## Releasing

Releases are tag-driven. Pushing a semver tag runs
[`.github/workflows/release.yml`](.github/workflows/release.yml), which builds,
runs the hermetic test suite, signs the artifacts, publishes every shipping
module to Maven Central and GitHub Packages, and creates a GitHub release with
the jars attached:

```bash
git tag v1.2.3 && git push origin v1.2.3
```

The version comes from the tag — nothing in the repository records it, and
ordinary builds stay on `0.1.0-SNAPSHOT`. Tags with a pre-release identifier
(`v1.2.3-rc.1`) are marked as pre-releases.

The workflow's *Run workflow* button never publishes. Left alone it just builds
and uploads the artifacts for inspection; tick **rehearse** and it signs them
and uploads to Maven Central for validation only, which proves the signing key,
portal token and keyserver all work before a tag makes anything permanent — then
you drop the deployment in the portal.

Modules applying the `nightjar.published` convention plugin are the ones that
ship; `examples`, `native-smoke` and `spring-boot-compat-check` deliberately
do not.

**A Maven Central release is permanent** — a published version can never be
replaced or removed, and this pipeline publishes automatically once Central's
validation passes. The hermetic build is the only gate between a tag and a
permanent artifact, so tag deliberately.

Releasing needs four repository secrets: `SIGNING_KEY` and `SIGNING_PASSWORD`
(the PGP key whose public half is on a keyserver), and `CENTRAL_USERNAME` /
`CENTRAL_PASSWORD` (a Maven Central portal user token).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
