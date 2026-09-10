package dev.nightjar.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for the nightjar Spring Boot starter — the commonly tuned
 * knobs. Every auto-configured bean is `@ConditionalOnMissingBean`: define
 * the bean yourself for anything beyond these properties.
 *
 * (JavaBean-style binding on purpose: no `kotlin-reflect` requirement for
 * consuming applications.)
 */
@ConfigurationProperties("nightjar")
public class NightjarProperties {

    public var domainEvent: DomainEvent = DomainEvent()
    public var migrations: Migrations = Migrations()
    public var coordination: Coordination = Coordination()

    public class DomainEvent {
        /** Master switch for the domain-event auto-configuration. */
        public var enabled: Boolean = true

        /** Which transport carries events. Default: in-process (single instance). */
        public var transport: Transport = Transport.IN_PROCESS

        /** Outbox relay poll cadence. */
        public var pollInterval: Duration = Duration.ofMillis(500)

        /** Max outbox records per relay poll. */
        public var batchSize: Int = 200

        /** Resend SENT records not processed within this duration. */
        public var redeliverAfter: Duration = Duration.ofHours(1)

        /**
         * Worker type whose suspension stops event delivery and processing
         * across the whole cluster — set its concurrency to 0 (e.g.
         * `workerPool.configure("nightjar-domain-events", 0)`) to pause, back to
         * 1 to resume. Only active when the worker pool is enabled
         * (`nightjar.coordination.worker.enabled=true`); otherwise the gate
         * stays open.
         */
        public var workerType: String = "nightjar-domain-events"

        public var rabbitmq: RabbitMq = RabbitMq()
    }

    public enum class Transport { IN_PROCESS, RABBITMQ }

    /** Used when `transport = RABBITMQ` (requires `dev.nightjar:domain-event-rabbitmq`). */
    public class RabbitMq {
        public var host: String = "localhost"
        public var port: Int = 5672
        public var username: String = "guest"
        public var password: String = "guest"
        public var queue: String = "nightjar.domain.events"
        public var prefetch: Int = 10
    }

    public class Migrations {
        /**
         * Apply discovered schema migrations during application startup
         * (requires `dev.nightjar:migrations-liquibase` on the classpath).
         * Default off — an init container is the recommended Cloud-Native shape.
         */
        public var applyOnStartup: Boolean = false

        public var data: DataMigrations = DataMigrations()
    }

    public class DataMigrations {
        /** Start the data-migration engine (background poller). */
        public var enabled: Boolean = false
        public var pollInterval: Duration = Duration.ofSeconds(5)
        public var batchSize: Int = 200
        public var sequentialBatchSize: Int = 25
        public var claimExpiry: Duration = Duration.ofMinutes(10)
    }

    public class Coordination {
        public var scheduler: Scheduler = Scheduler()
        public var worker: Worker = Worker()
    }

    public class Scheduler {
        /** Start the simple scheduler (background poller). */
        public var enabled: Boolean = false
        public var pollInterval: Duration = Duration.ofSeconds(60)
        public var retryDelay: Duration = Duration.ofMinutes(5)
        public var fireLockTtl: Duration = Duration.ofMinutes(10)
        public var batchSize: Int = 100
    }

    public class Worker {
        /** Expose a DB-backed WorkerPool bean (background permit sweeper). */
        public var enabled: Boolean = false
        public var permitTtl: Duration = Duration.ofMinutes(10)
        public var cleanupInterval: Duration = Duration.ofSeconds(10)
        public var defaultConcurrency: Int = 1
    }
}
