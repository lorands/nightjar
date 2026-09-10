package dev.nightjar.spring

import dev.nightjar.domainevent.DomainEvent
import dev.nightjar.domainevent.DomainEventListener
import dev.nightjar.domainevent.DomainEventPublisher
import dev.nightjar.domainevent.SyncDomainEventListener
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Jackson-friendly without jackson-module-kotlin: default ctor + mutable property. */
class AuditedAction @JvmOverloads constructor(var actionId: String = "") : DomainEvent

/**
 * Transactional publish, supplied by the starter: a bare
 * `publish()` call — no surrounding `@Transactional` — still runs sync
 * listeners and the outbox append atomically, because [NightjarDomainEvents]
 * opens a `REQUIRED` transaction when none is active.
 */
@SpringBootTest(classes = [TestApp::class, BarePublishTransactionTest.Listeners::class])
class BarePublishTransactionTest(
    private val publisher: DomainEventPublisher,
    dataSource: DataSource,
) {

    companion object {
        var delivered = CountDownLatch(1)
    }

    private val jdbc = JdbcTemplate(dataSource)

    @TestConfiguration(proxyBeanMethods = false)
    class Listeners {

        /** Sync listener doing its own JDBC work — must share publish's transaction. */
        @Bean
        @Order(1)
        fun auditWriter(dataSource: DataSource): SyncDomainEventListener<AuditedAction> =
            SyncDomainEventListener { envelope ->
                JdbcTemplate(dataSource).update("INSERT INTO audit_row (id) VALUES (?)", envelope.event.actionId)
            }

        /** Sync listener enforcing a domain rule — vetoes specific publishes. */
        @Bean
        @Order(2)
        fun veto(): SyncDomainEventListener<AuditedAction> = SyncDomainEventListener { envelope ->
            check(envelope.event.actionId != "vetoed") { "domain rule violated" }
        }

        @Bean
        fun asyncListener(): DomainEventListener<AuditedAction> = DomainEventListener {
            delivered.countDown()
        }
    }

    @BeforeTest
    fun setUp() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS audit_row (id VARCHAR(100) PRIMARY KEY)")
        jdbc.update("DELETE FROM audit_row")
        delivered = CountDownLatch(1)
        // let any in-flight event from a previous test drain (the outbox delete
        // commits with the listener transaction, after the delivery latch)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && outboxCount() != 0) Thread.sleep(10)
    }

    private fun outboxCount(): Int = jdbc.queryForObject("SELECT COUNT(*) FROM domain_event", Int::class.java)!!

    @Test
    fun `bare publish outside any transaction delivers and commits the sync listener's work`() {
        publisher.publish(AuditedAction("plain"))

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_row", Int::class.java))
        assertTrue(delivered.await(10, TimeUnit.SECONDS), "async listener was not invoked")
    }

    @Test
    fun `bare publish with a failing sync listener rolls back sibling sync work and appends nothing`() {
        assertFailsWith<IllegalStateException> { publisher.publish(AuditedAction("vetoed")) }

        // without the publish-owned transaction, the audit write would have auto-committed
        assertEquals(
            0,
            jdbc.queryForObject("SELECT COUNT(*) FROM audit_row", Int::class.java),
            "sync listener's write must roll back with the vetoed publish",
        )
        assertEquals(0, outboxCount(), "no outbox record may exist for a vetoed publish")
    }
}
