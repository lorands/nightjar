# migrations-liquibase

Schema-migration execution for the [migrations](../migrations/) convention,
powered by Liquibase.

**Dependency: `org.liquibase:liquibase-core`** — and that is deliberate: this
module runs at **deploy time** (init container, CLI step, CI job). Your
application never carries Liquibase at runtime.

## Installation

```kotlin
dependencies {
    implementation("dev.nightjar:migrations-liquibase:0.1.0-SNAPSHOT")
    runtimeOnly("org.postgresql:postgresql:<version>") // your driver
}
```

## Usage

### Programmatic

```kotlin
LiquibaseMigrator().migrate(jdbcUrl, username, password)
// or over an existing connection:
LiquibaseMigrator().migrate(connection)
```

Discovers every `META-INF/nightjar/migrations.properties` manifest on the
classpath, aggregates the changelogs in manifest order, and runs Liquibase
`update`. Tracked in the standard `DATABASECHANGELOG` table — re-running is a
no-op. The changelogs reference each module's **pristine SQL** (`sqlFile`) —
SQL is never transformed.

### CLI / init container

```bash
java -cp <app-classpath> dev.nightjar.migrations.liquibase.Main \
    "$NIGHTJAR_DB_URL"   # or set NIGHTJAR_DB_URL / _USERNAME / _PASSWORD env vars
```

Typical Kubernetes shape: an init container with the application image running
the line above; the app container starts only after schemas are current.

### Alternative: your framework's Liquibase

Spring Boot / Quarkus Liquibase integrations can consume the same module
changelogs directly — include them from your master changelog
(`<include file="db/changelog/domain-event-jdbc-changelog.xml"/>`); the
manifest convention is then just documentation of what to include.
