# nightjar

Kotlin libraries providing DDD (Domain-Driven Design) building blocks for JVM applications.
Hard requirements live in [REQUIREMENTS.md](REQUIREMENTS.md) — read it before designing anything.

## Hard requirements (non-negotiable)

1. **Minimum dependencies** — no framework dependencies; avoid runtime dependencies wherever possible. Core modules should depend on `kotlin-stdlib` only.
2. **Maximum compatibility** — design against abstractions; ship at most one optional implementation as a *separate adapter module* (e.g. RabbitMQ), leaving room for others (NATS, Kafka, ...). Never let an implementation choice leak into a core module.
3. **Small and lean** — minimal, focused codebase. Prefer fewer concepts over more features.
4. **DDD and Cloud-Native** — APIs follow DDD language and patterns; libraries must behave well in containers/orchestrated environments: externalized configuration, no local-state assumptions, graceful shutdown (`AutoCloseable` lifecycles), multi-instance safety (e.g. DB-level locking, atomic claiming), observability hooks.
5. **Don't reinvent the wheel** — before designing a feature, research existing solutions; adopt one only if it aligns with these requirements AND is open source, well-maintained, actively developed. Otherwise build minimal ourselves.
6. **Examples are mandatory** — every feature ships examples in **both Java and Kotlin**, plus wiring examples for **Spring Boot and Quarkus** (the libraries stay framework-free; the wiring examples show host integration).
7. **Documentation is mandatory** — per feature: a `README.md` (installation, usage examples, dependency list), KDoc on the public API, and a user manual under `docs/` in **AsciiDoc** format.
8. **High-quality tests for all features** — behavior-level tests (not just unit), including integration tests proving the headline guarantees.

### Definition of done for a module/feature

Code (explicit API, Java-friendly, native-image-friendly) → tests (unit + integration) → README.md → AsciiDoc user manual under `docs/` → Java + Kotlin examples → Spring Boot + Quarkus wiring examples. If core runtime paths changed: `./gradlew :native-smoke:nativeRun` still passes.

## Modules

| Module | Purpose | Runtime deps |
|---|---|---|
| `coordination` | Cluster primitives: `ProcessLock` (leased mutex), `WorkerPool` (throttle), `SimpleScheduler` (persistent timers) — see [docs/coordination-design.md](docs/coordination-design.md) | none (depends on `domain-event`) |
| `coordination-jdbc` | DB-backed lock/worker/scheduler; ships its schema via the manifest convention | none (`java.sql`) |
| `domain-event` | Domain-event model, bus, transactional-outbox relay, retry/suspension, in-memory impls — see [docs/domain-event-design.md](docs/domain-event-design.md) | none |
| `domain-event-jdbc` | `OutboxStore` over plain JDBC (+ `JdbcTransactions` for framework-free TX); ships its schema via the manifest convention | none (`java.sql`) |
| `domain-event-rabbitmq` | `EventTransport` for RabbitMQ + poison-message dead-lettering | `com.rabbitmq:amqp-client` |
| `migrations` | Schema-migration manifest convention + data-migration engine — see [docs/migrations-design.md](docs/migrations-design.md) | none (depends on `domain-event`) |
| `migrations-jdbc` | `DataMigrationStore` over plain JDBC; ships its own schema via the manifest convention | none (`java.sql`) |
| `migrations-liquibase` | `LiquibaseMigrator` + CLI `Main` — aggregates discovered changelogs, deploy-time only | `liquibase-core` |
| `nightjar-spring-boot-starter` | Auto-configuration for the full surface — THE sanctioned framework-dependent module (Boot 3.5 baseline; see [docs/spring-boot-starter-design.md](docs/spring-boot-starter-design.md)) | Spring Boot |
| `examples` | Runnable Java/Kotlin examples (executed as smoke tests) + Spring/Quarkus wiring (compile-checked via `compileOnly` framework APIs) — not published, explicit API disabled | — |
| `native-smoke` | GraalVM native-image verification harness (`:native-smoke:nativeRun`) — not published, not part of `build` | — |
| `spring-boot-compat-check` | Boots the starter on Spring Boot 4 in the hermetic build — not published | — |

More to come. Naming pattern for implementations: `<core>-<tech>` (e.g. `domain-event-rabbitmq`) — core modules hold interfaces/contracts, adapter modules hold one optional tech binding and its dependencies. Compatibility layers that reproduce a third-party API live in their own repositories and consume nightjar as a published Gradle dependency — never add such a module, or a filesystem link to one, here.

### Adding a new module

1. `include("name")` in `settings.gradle.kts`
2. Create `name/build.gradle.kts` applying `id("nightjar.kotlin-library")` — plus `id("nightjar.published")` if it ships to consumers, and `id("nightjar.integration-test")` if it needs real services
3. Published modules set a `description` (it becomes the POM description)
4. Dependencies/versions go through `gradle/libs.versions.toml` — never inline versions
5. If the module owns database tables: contribute the schema via the migrations convention (manifest + changelog + pristine SQL)
6. If it reads classpath resources at runtime: register them in `META-INF/native-image/dev.nightjar/<module>/resource-config.json`

## API design rules

- Libraries are consumed from **Java as well as Kotlin** — keep the public API Java-friendly:
  - `@JvmStatic`, `@JvmOverloads`, `@JvmField`, `@file:JvmName` where applicable
  - no `suspend`, inline/reified, or Kotlin function types on the public API boundary; prefer `fun interface` or `java.util.function` types
- `explicitApi()` is enforced — all public declarations need explicit visibility and return types
- Compatibility floor is **Java 17**: no APIs newer than JDK 17 anywhere (the toolchain enforces this)
- **GraalVM native-image friendliness is a design constraint**: no reflection, no classpath scanning, no `Class.forName`, no dynamic proxies, no static-init of threads/`SecureRandom`. Classpath resources read at runtime need an entry in that module's `META-INF/native-image/dev.nightjar/<module>/resource-config.json`. Verify with `./gradlew :native-smoke:nativeRun` (GraalVM auto-provisioned via toolchains). `migrations-liquibase` is exempt (deploy-time JVM tool)

## Schema migrations convention

Modules contribute schema via `META-INF/nightjar/migrations.properties` (`id`, `changelog`, `order`) + a Liquibase changelog that ONLY references pristine `.sql` files via `sqlFile` — **SQL is never transformed into Liquibase change types** (hard user requirement). Liquibase lives exclusively in `migrations-liquibase` (deploy-time).

## Build

Gradle (Kotlin DSL), multi-module, shared config via the `nightjar.kotlin-library` convention plugin in `buildSrc/src/main/kotlin/`. Modules with real-service tests also apply `nightjar.integration-test` (separate `integrationTest` source set, NOT in `check`).

```bash
./gradlew build               # hermetic: compile + unit/H2/mock tests
./gradlew :domain-event:test  # one module

devbox services up            # real services: PostgreSQL :6543, RabbitMQ :5672
./gradlew integrationTest     # real-service tests; skip gracefully when services down

./gradlew :native-smoke:nativeRun   # GraalVM AOT verification (slow; not in build)
```

Integration tests use JUnit assumptions to skip when services are unreachable; PostgreSQL deliberately runs on port 6543 (no collision with system/docker instances). devbox (Nix-based) replaces testcontainers — user decision: no Docker daemon dependency in pipelines.

### JVM layers (all pinned deliberately — don't change casually)

| Layer | Version | Pinned by |
|---|---|---|
| Gradle daemon | 25 LTS | `gradle/gradle-daemon-jvm.properties` |
| buildSrc build logic | 17 | toolchain in `buildSrc/build.gradle.kts` |
| Library compile target / bytecode | 17 | `jvmToolchain(17)` in the convention plugin |
| Native AOT (native-smoke only) | GraalVM CE 25 | toolchain spec in `native-smoke/build.gradle.kts` (auto-provisioned) |

- Raising the Java 17 floor is a breaking change for consumers — requires a deliberate decision.
- Prefer LTS Java versions (17/21/25) for any tooling or runtime choice.
- Repositories are settings-managed (`FAIL_ON_PROJECT_REPOS`): `mavenCentral()` only.
- Group: `dev.nightjar`. Version defaults to `0.1.0-SNAPSHOT` and is overridden with `-Pversion=<semver>` — the release workflow derives it from the git tag. Never hard-code a release version anywhere in the repo.

## Licensing and releases

Apache License 2.0 ([LICENSE](LICENSE)); the POM carries the license, SCM and
project URL. Per-file license headers are deliberately not used.

`nightjar.published` is the convention plugin that makes a module shippable:
`maven-publish`, a sources jar, POM metadata, and a `staging` directory
repository at `build/staging-repo`. `examples`, `native-smoke` and
`spring-boot-compat-check` must never apply it.

Releases are driven entirely by git tags via `.github/workflows/release.yml`
(`v1.2.3`, or `v1.2.3-rc.1` for a pre-release). The workflow builds, runs the
hermetic suite, stages the artifacts, publishes them to GitHub Packages and
creates the GitHub release with the jars attached. Its *Run workflow* button is
a dry run — it skips both the registry publish and the release, since GitHub
Packages will not overwrite a version once published. Local equivalent:

```bash
./gradlew build publishAllPublicationsToStagingRepository -Pversion=1.2.3
```

A tag release goes to three places: **Maven Central** (primary), **GitHub
Packages**, and the GitHub release's attached jars.

Group id is `com.lorands.nightjar`, verified on Central through the
`com.lorands` namespace (DNS TXT on lorands.com; the namespace covers every
`com.lorands.*` subgroup). Kotlin packages stay `dev.nightjar.*` — package
names and Maven coordinates are independent, and renaming them would break
every consumer import for no gain. Don't "fix" the mismatch.

Central's mandatory extras, all wired into `nightjar.published`:

- a **javadoc jar** per module, generated from KDoc by Dokka (`javadocJar`)
- a **sources jar** (`withSourcesJar()`)
- a **PGP signature** beside every file, via the `signing` plugin with an
  in-memory key
- POM `name`/`description`/`url`/`licenses`/`developers`/`scm` — all present;
  don't drop any

Credentials are all optional-by-absence, so ordinary builds,
`publishToMavenLocal` and the staging publish need none:

| Secret | Used for |
|---|---|
| `SIGNING_KEY` / `SIGNING_PASSWORD` | armored PGP key; a *blank* key counts as absent (CI always passes the env) |
| `CENTRAL_USERNAME` / `CENTRAL_PASSWORD` | Central portal user token |
| `githubPackagesUsername`/`Password` | from `github.actor` + `GITHUB_TOKEN`, needs `packages: write` |

The Central upload is a plain `curl` of the signed staging repo zipped as a
bundle (`maven-metadata.xml` excluded — Central generates its own), posted to
`central.sonatype.com/api/v1/publisher/upload` with
`publishingType=AUTOMATIC`, then polled to `PUBLISHED`. No third-party
publishing plugin.

Central runs **first** of the three publish steps, deliberately: it is the most
likely to fail (signature, keyserver and POM validation) and the only
irreversible one, so it gates the rest. GitHub Packages refuses to overwrite a
version, so publishing there first would burn the version number whenever
Central rejected the bundle. Don't reorder these.

### Secret handling

Secrets on a public repository are safe because of *what can reach them*, not
because the repository is private:

- The release workflow's only triggers are tag pushes and `workflow_dispatch`.
  Neither is reachable from a fork. Never add `pull_request_target`,
  `workflow_run`, or `issue_comment` here — those run with secret access and
  are the standard way public repos leak credentials.
- The signing key is bound to tag builds by expression, so it is absent from
  the environment of every dry run.
- No step ever echoes a secret. GitHub masks them in logs, but masking is
  string matching — a transformed secret (base64, split, reversed) prints in
  clear. Keep it that way.
- Only official `actions/*` and `gradle/actions` are used. Every third-party
  action added here can read the job's environment.

Residual risk worth knowing: anyone with write access can publish a workflow
that exfiltrates the secrets, and the Gradle build runs with the signing key
in its environment, so a hostile build plugin or dependency could read it.
Neither is fixable with configuration; both argue for keeping write access
narrow and the build's plugin list short.

The signing key's **public half must be resolvable from a keyserver** or Central
rejects the bundle. `keyserver.ubuntu.com` propagates across a cluster over
hours — check availability before tagging, since a partially propagated key
fails validation intermittently.

**A Maven Central release is permanent**: a version can never be replaced or
removed, and AUTOMATIC means no human gate. The hermetic build is the only
thing between a tag and a permanent artifact.

GitHub Action versions are pinned to major tags and should be kept current.
