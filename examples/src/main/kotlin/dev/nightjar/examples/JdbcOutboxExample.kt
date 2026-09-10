package dev.nightjar.examples

import dev.nightjar.domainevent.DomainEventBus
import dev.nightjar.domainevent.inmemory.InProcessEventTransport
import dev.nightjar.domainevent.jdbc.JdbcOutboxStore
import dev.nightjar.domainevent.jdbc.JdbcTransactions
import org.h2.jdbcx.JdbcDataSource
import java.time.Duration
import javax.sql.DataSource

/**
 * The transactional-outbox guarantee with a real database (H2 here, PostgreSQL
 * in production): an event published inside a committed transaction is
 * delivered; a rolled-back transaction leaves no trace — no ghost events.
 *
 * `JdbcTransactions` is nightjar's framework-free transaction helper; with
 * Spring or Quarkus you adapt their transaction management instead (see the
 * wiring examples).
 */
object JdbcOutboxExample {

    @JvmStatic
    fun main(args: Array<String>) {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:nightjar-example;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
        }
        createSchema(dataSource)

        val transactions = JdbcTransactions(dataSource)
        val store = JdbcOutboxStore(dataSource, transactions)
        val bus = DomainEventBus.builder()
            .serializer(ExampleSerializer)
            .store(store)
            .transport(InProcessEventTransport()) // production: RabbitMqEventTransport(factory, "myapp.domain.events")
            .transactionalRunner(transactions)
            .pollInterval(Duration.ofMillis(20))
            .build()

        bus.subscribe(OrderPlaced::class.java) { envelope ->
            println("[async] delivered after commit: ${envelope.event.orderId}")
        }

        bus.start()
        bus.use {
            // Committed transaction: business data and event commit together,
            // the relay picks the event up and delivers it.
            transactions.run {
                bus.publish(OrderPlaced("order-committed", 100))
            }

            // Rolled-back transaction: the outbox append is discarded with the
            // business data — the listener never sees "order-ghost".
            runCatching {
                transactions.run {
                    bus.publish(OrderPlaced("order-ghost", 666))
                    error("business rule failed after publish")
                }
            }

            Thread.sleep(400) // demo only
        }
    }

    private fun createSchema(dataSource: DataSource) {
        // In production, ship src/main/resources/.../domain-event-postgresql.sql
        // from domain-event-jdbc through your migration tool. This is the
        // H2-compatible equivalent (H2 lacks partial indexes).
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE domain_event (
                        id           VARCHAR(36)  PRIMARY KEY,
                        event_type   VARCHAR(250) NOT NULL,
                        payload      BYTEA        NOT NULL,
                        metadata     TEXT,
                        sequence_key VARCHAR(250),
                        status       VARCHAR(20)  NOT NULL,
                        attempts     INT          NOT NULL DEFAULT 0,
                        created_at   TIMESTAMP    NOT NULL,
                        modified_at  TIMESTAMP,
                        suspended    BOOLEAN      NOT NULL DEFAULT FALSE,
                        last_error   TEXT
                    )
                    """.trimIndent(),
                )
                statement.execute("CREATE INDEX idx_domain_event_pending ON domain_event (created_at)")
                statement.execute("CREATE TABLE domain_event_lock (lock_key VARCHAR(250) PRIMARY KEY)")
            }
        }
    }
}
