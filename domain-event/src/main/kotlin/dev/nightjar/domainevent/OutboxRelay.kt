package dev.nightjar.domainevent

import dev.nightjar.domainevent.spi.EventObserver
import dev.nightjar.domainevent.spi.EventTransport
import dev.nightjar.domainevent.spi.OutboxRecord
import dev.nightjar.domainevent.spi.OutboxStatus
import dev.nightjar.domainevent.spi.OutboxStore
import dev.nightjar.domainevent.spi.ProcessingGate
import dev.nightjar.domainevent.spi.RetryPolicy
import dev.nightjar.domainevent.spi.TransportMessage
import java.lang.System.Logger.Level
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Polls the outbox store and hands due records to the transport — the single
 * mechanism behind initial delivery, redelivery of lost messages and timed
 * retries of failed processing.
 */
internal class OutboxRelay(
    private val store: OutboxStore,
    private val transport: EventTransport,
    private val retryPolicy: RetryPolicy,
    private val clock: Clock,
    private val observer: EventObserver,
    private val pollInterval: Duration,
    private val batchSize: Int,
    private val redeliverAfter: Duration,
    private val processingGate: ProcessingGate = ProcessingGate.OPEN,
) : AutoCloseable {

    private val log = System.getLogger(OutboxRelay::class.java.name)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nightjar-outbox-relay").apply { isDaemon = true }
    }

    fun start() {
        scheduler.scheduleWithFixedDelay(
            ::safePoll, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS,
        )
    }

    private fun safePoll() {
        try {
            pollOnce()
        } catch (e: Exception) {
            log.log(Level.ERROR, "Outbox poll failed", e)
        }
    }

    // Visible for tests: a single deterministic poll pass.
    internal fun pollOnce() {
        // Cluster-wide stop: send nothing; records keep their state and flow
        // again on the first poll after the gate reopens.
        if (!processingGate.isOpen()) return
        val now = clock.instant()
        for (record in store.findPending(batchSize)) {
            try {
                relay(record, now)
            } catch (e: Exception) {
                log.log(Level.ERROR, "Relaying event ${record.id} failed", e)
            }
        }
    }

    private fun relay(record: OutboxRecord, now: java.time.Instant) {
        when (record.status) {
            OutboxStatus.CREATED -> send(record)

            // Sent but neither processed nor failed for a long time: the message
            // was lost (broker restart, consumer crash before claim) — resend.
            OutboxStatus.SENT -> {
                val sentAt = record.modifiedAt ?: record.createdAt
                if (Duration.between(sentAt, now) >= redeliverAfter) send(record)
            }

            OutboxStatus.ERROR ->
                if (retryPolicy.isExhausted(record.createdAt, now)) {
                    store.markSuspended(record.id)
                    observer.suspended(record.id, record.eventType)
                    log.log(Level.WARNING, "Event ${record.id} (${record.eventType}) exhausted its retry window — suspended")
                } else if (retryPolicy.isRetryDue(record.createdAt, record.modifiedAt ?: record.createdAt, now)) {
                    send(record)
                }

            OutboxStatus.PROCESSING -> {} // not eligible; stores should not return these
        }
    }

    private fun send(record: OutboxRecord) {
        // Mark SENT *before* the handoff. The moment the transport accepts a
        // message a consumer may claim, process and either delete the record or
        // mark it ERROR — on the transport's own thread here, on another instance
        // with a real broker. Marking afterwards races that and overwrites the
        // outcome back to SENT, and the relay then leaves a SENT record alone
        // until `redeliverAfter` (an hour by default) instead of retrying it on
        // the retry policy's schedule.
        store.markSent(record.id)
        try {
            transport.send(TransportMessage(record.id, record.eventType, record.payload, record.metadata))
        } catch (e: Exception) {
            // Nothing was handed over, so no consumer will move the record off
            // SENT — record the failure to put it back on the retry schedule.
            store.markError(record.id, e.stackTraceToString())
            throw e
        }
    }

    override fun close() {
        scheduler.shutdown()
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
            scheduler.shutdownNow()
        }
    }
}
