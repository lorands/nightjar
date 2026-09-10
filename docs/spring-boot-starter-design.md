# nightjar-spring-boot-starter — Design

The deliberate exception to REQUIREMENTS #1: this module's whole purpose is
framework integration (user-authorized). The nightjar libraries stay
framework-free; everything Spring lives here.

## Locked decisions

| # | Decision |
|---|---|
| 1 | Listener model: beans implementing `DomainEventListener<E>` / `SyncDomainEventListener<E>` are auto-subscribed; event class resolved via Spring `ResolvableType` (proxy-aware), `@Order` respected |
| 2 | `EventSerializer`: Jackson-based by default, type registry derived from subscribed listeners' event classes (allowlist preserved); any user-defined `EventSerializer` bean backs the default off |
| 3 | Baseline: compile against Boot 3.5.x; Boot 4.x verified empirically by the unpublished `spring-boot-compat-check` module in every hermetic build |
| 4 | v1 covers the full surface: domain-event, migrations (schema apply-on-startup + data engine), coordination — each independently usable/toggleable |

## Key design points

- **TX bridge is the heart**: `TransactionalConnectionSource` via
  `DataSourceUtils` + active-transaction check, `TransactionalRunner` via
  `TransactionTemplate(REQUIRES_NEW)`. Publish/enqueue/schedule inside
  `@Transactional` code is atomic with the business change.
- **Lazy bus assembly breaks bean cycles**: listeners commonly inject
  `DomainEventPublisher`; the publisher bean is a thin delegate, and the real
  `DomainEventBus` is assembled in `SmartLifecycle.start()` (after all beans
  exist): resolve listener generics → build serializer registry → subscribe in
  `@Order` order → start. Publishing before lifecycle start throws.
- **Lifecycle**: engines (bus, data-migration engine, scheduler, worker pool
  cleanup) start in `SmartLifecycle` phases after context refresh and stop
  first on shutdown — container-graceful.
- **Optional integrations stay optional**: `domain-event-rabbitmq` and
  `migrations-liquibase` are `compileOnly` + `@ConditionalOnClass`; apps add
  the dependency to opt in. Liquibase never rides into apps that migrate via
  init container (the documented default; `nightjar.migrations.apply-on-startup`
  exists for the others).
- **Jackson compatibility**: the serializer is conditional on
  `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2). It uses the app's
  `ObjectMapper` bean when one exists, otherwise builds its own — Boot 4
  (Jackson 3 default) keeps working as long as jackson-databind 2.x is on the
  classpath (the starter brings it).
- **Configuration** under `nightjar.*` via `@ConfigurationProperties`, mapping
  the commonly tuned builder knobs; full builder remains reachable by defining
  the corresponding bean yourself (every auto-config is
  `@ConditionalOnMissingBean`).

## Module layout

```
nightjar-spring-boot-starter/      published; Boot 3.5 baseline
  NightjarProperties
  NightjarTransactionAutoConfiguration
  NightjarDomainEventAutoConfiguration   (+ serializer, lifecycle, publisher delegate)
  NightjarMigrationsAutoConfiguration    (+ DataMigrationHandlerBean marker interface)
  NightjarCoordinationAutoConfiguration  (+ ScheduledJobBean marker interface)
  META-INF/spring/...AutoConfiguration.imports
spring-boot-compat-check/          NOT published; same smoke on Boot 4.x
```

## Out of scope (follow-ups)

- Annotation-driven handler methods (`@NightjarEventHandler`) — interface
  beans first
- Micrometer `EventObserver` bridge auto-config
- Spring Boot AOT/native hints for the starter itself (the core libraries are
  already native-friendly; starter AOT needs its own verification pass)
- Quarkus extension (symmetric story for the other supported framework)
