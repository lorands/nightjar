package dev.nightjar.domainevent

import dev.nightjar.domainevent.spi.EventObserver
import dev.nightjar.domainevent.spi.EventSerializer
import dev.nightjar.domainevent.spi.EventTransport
import dev.nightjar.domainevent.spi.IdGenerator
import dev.nightjar.domainevent.spi.MetadataProvider
import dev.nightjar.domainevent.spi.OutboxRecord
import dev.nightjar.domainevent.spi.OutboxStatus
import dev.nightjar.domainevent.spi.OutboxStore
import dev.nightjar.domainevent.spi.ProcessingGate
import dev.nightjar.domainevent.spi.RetryPolicy
import dev.nightjar.domainevent.spi.TransactionalRunner
import dev.nightjar.domainevent.spi.TransportMessage
import java.lang.System.Logger.Level
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The central engine: publishes events (sync dispatch + outbox append),
 * relays the outbox to the transport, and dispatches incoming messages to
 * asynchronous listeners with retry and suspension.
 *
 * Wire it once at application startup:
 * ```
 * val bus = DomainEventBus.builder()
 *     .serializer(mySerializer)
 *     .store(myStore)
 *     .transport(myTransport)
 *     .build()
 * bus.subscribe(OrderPlaced::class.java) { envelope -> ... }
 * bus.start()
 * ```
 */
public class DomainEventBus private constructor(builder: Builder) : DomainEventPublisher, AutoCloseable {

    private val log = System.getLogger(DomainEventBus::class.java.name)

    private val serializer = requireNotNull(builder.serializer) { "serializer is required" }
    private val store = requireNotNull(builder.store) { "store is required" }
    private val transport = requireNotNull(builder.transport) { "transport is required" }
    private val transactionalRunner = builder.transactionalRunner
    private val idGenerator = builder.idGenerator
    private val metadataProvider = builder.metadataProvider
    private val observer = builder.observer
    private val clock = builder.clock
    private val processingGate = builder.processingGate

    private val relay = OutboxRelay(
        store, transport, builder.retryPolicy, clock, observer,
        builder.pollInterval, builder.batchSize, builder.redeliverAfter, processingGate,
    )

    private val syncListeners = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<Registration>>()
    private val asyncListeners = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<Registration>>()
    private val registrationSeq = AtomicInteger()
    private val started = AtomicBoolean()

    // ----------------------------------------------------------------- subscribe

    /** Register an asynchronous listener for [eventClass] with default order 0. */
    public fun <E : DomainEvent> subscribe(eventClass: Class<E>, listener: DomainEventListener<E>) {
        subscribe(eventClass, 0, listener)
    }

    /** Register an asynchronous listener; lower [order] runs first, ties run in registration order. */
    public fun <E : DomainEvent> subscribe(eventClass: Class<E>, order: Int, listener: DomainEventListener<E>) {
        register(asyncListeners, eventClass, order, listener)
    }

    /** Register a synchronous (in-publishing-transaction) listener with default order 0. */
    public fun <E : DomainEvent> subscribeSync(eventClass: Class<E>, listener: SyncDomainEventListener<E>) {
        subscribeSync(eventClass, 0, listener)
    }

    /** Register a synchronous listener; lower [order] runs first, ties run in registration order. */
    public fun <E : DomainEvent> subscribeSync(eventClass: Class<E>, order: Int, listener: SyncDomainEventListener<E>) {
        register(syncListeners, eventClass, order, listener)
    }

    private fun register(
        registry: ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<Registration>>,
        eventClass: Class<*>,
        order: Int,
        listener: Any,
    ) {
        val list = registry.computeIfAbsent(eventClass) { CopyOnWriteArrayList() }
        list.add(Registration(order, registrationSeq.getAndIncrement(), listener))
        list.sortWith(compareBy({ it.order }, { it.seq }))
    }

    // ------------------------------------------------------------------- publish

    override fun publish(event: DomainEvent) {
        val envelope = EventEnvelope(
            idGenerator.nextId(), event.eventType(), clock.instant(), metadataProvider.current(), event,
        )

        // Sync listeners run first, inline, in the caller's
        // transaction — their exceptions propagate and roll everything back.
        dispatch(syncListeners, envelope) { registration, env ->
            @Suppress("UNCHECKED_CAST")
            (registration.listener as SyncDomainEventListener<DomainEvent>).onEvent(env)
        }

        store.append(
            OutboxRecord(
                id = envelope.id,
                eventType = envelope.type,
                payload = serializer.serialize(event),
                metadata = envelope.metadata,
                sequenceKey = event.sequenceKey(),
                status = OutboxStatus.CREATED,
                attempts = 0,
                createdAt = envelope.occurredAt,
                modifiedAt = null,
                suspended = false,
                lastError = null,
            ),
        )
        observer.published(envelope)
    }

    // ----------------------------------------------------------------- lifecycle

    /** Start consuming from the transport and relaying the outbox. */
    public fun start() {
        check(!started.getAndSet(true)) { "DomainEventBus already started" }
        transport.startConsuming { message -> handleMessage(message) }
        relay.start()
    }

    override fun close() {
        if (started.get()) {
            relay.close()
            transport.close()
        }
    }

    // ---------------------------------------------------------------- consuming

    private fun handleMessage(message: TransportMessage) {
        try {
            // Cluster-wide stop: drop unclaimed — the record keeps its state
            // and the relay re-sends it once the gate reopens.
            if (!processingGate.isOpen()) {
                log.log(Level.DEBUG, "Processing gate closed — dropping message for event ${message.eventId}")
                return
            }
            // Atomic claim: returns null if already processed, suspended, or
            // claimed by another instance — then the message is simply dropped.
            val record = store.claim(message.eventId) ?: return
            process(record)
        } catch (e: Exception) {
            log.log(Level.ERROR, "Failed to handle message for event ${message.eventId}", e)
        }
    }

    private fun process(record: OutboxRecord) {
        val envelope = try {
            val event = serializer.deserialize(record.eventType, record.payload)
            EventEnvelope(record.id, record.eventType, record.createdAt, record.metadata, event)
        } catch (e: Exception) {
            store.markError(record.id, e.stackTraceToString())
            log.log(Level.ERROR, "Failed to deserialize event ${record.id} (${record.eventType})", e)
            return
        }

        try {
            // ONE transaction wraps every async listener
            // AND the outbox delete — a failing listener rolls the whole set
            // back (no partially committed listener work), and the record only
            // disappears together with the listeners' committed effects. The
            // sequence lock is held outermost, spanning that transaction.
            val processing = Runnable {
                transactionalRunner.run {
                    dispatchAsync(envelope)
                    store.delete(record.id)
                }
            }
            val sequenceKey = record.sequenceKey
            if (sequenceKey != null) {
                store.withSequenceLock(sequenceKey, processing)
            } else {
                processing.run()
            }
            observer.processed(envelope)
        } catch (e: Exception) {
            store.markError(record.id, e.stackTraceToString())
            observer.failed(envelope, e)
            log.log(Level.WARNING, "Processing event ${record.id} (${record.eventType}) failed; will retry", e)
        }
    }

    private fun dispatchAsync(envelope: EventEnvelope<DomainEvent>) {
        dispatch(asyncListeners, envelope) { registration, env ->
            @Suppress("UNCHECKED_CAST")
            (registration.listener as DomainEventListener<DomainEvent>).onEvent(env)
        }
    }

    private fun dispatch(
        registry: ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<Registration>>,
        envelope: EventEnvelope<DomainEvent>,
        invoke: (Registration, EventEnvelope<DomainEvent>) -> Unit,
    ) {
        val listeners = registry[envelope.event.javaClass]
        if (listeners.isNullOrEmpty()) {
            if (registry === asyncListeners) {
                log.log(Level.WARNING, "No listeners for event type ${envelope.type} — event ${envelope.id} dropped")
            }
            return
        }
        for (registration in listeners) {
            invoke(registration, envelope)
        }
    }

    private class Registration(val order: Int, val seq: Int, val listener: Any)

    // ------------------------------------------------------------------- builder

    public companion object {

        @JvmStatic
        public fun builder(): Builder = Builder()
    }

    public class Builder internal constructor() {
        internal var serializer: EventSerializer? = null
        internal var store: OutboxStore? = null
        internal var transport: EventTransport? = null
        internal var transactionalRunner: TransactionalRunner = TransactionalRunner.DIRECT
        internal var idGenerator: IdGenerator = UuidV7Generator()
        internal var metadataProvider: MetadataProvider = MetadataProvider.EMPTY
        internal var observer: EventObserver = EventObserver.NONE
        internal var processingGate: ProcessingGate = ProcessingGate.OPEN
        internal var retryPolicy: RetryPolicy = TimeWindowRetryPolicy()
        internal var clock: Clock = Clock.systemUTC()
        internal var pollInterval: Duration = Duration.ofMillis(500)
        internal var batchSize: Int = 200
        internal var redeliverAfter: Duration = Duration.ofHours(1)

        public fun serializer(serializer: EventSerializer): Builder = apply { this.serializer = serializer }
        public fun store(store: OutboxStore): Builder = apply { this.store = store }
        public fun transport(transport: EventTransport): Builder = apply { this.transport = transport }
        public fun transactionalRunner(runner: TransactionalRunner): Builder = apply { this.transactionalRunner = runner }
        public fun idGenerator(idGenerator: IdGenerator): Builder = apply { this.idGenerator = idGenerator }
        public fun metadataProvider(provider: MetadataProvider): Builder = apply { this.metadataProvider = provider }
        public fun observer(observer: EventObserver): Builder = apply { this.observer = observer }

        /** Cluster-wide stop switch; default [ProcessingGate.OPEN]. */
        public fun processingGate(gate: ProcessingGate): Builder = apply { this.processingGate = gate }

        public fun retryPolicy(retryPolicy: RetryPolicy): Builder = apply { this.retryPolicy = retryPolicy }
        public fun clock(clock: Clock): Builder = apply { this.clock = clock }

        /** How often the relay polls the outbox. Default 500 ms. */
        public fun pollInterval(pollInterval: Duration): Builder = apply { this.pollInterval = pollInterval }

        /** Maximum records per relay poll. Default 200. */
        public fun batchSize(batchSize: Int): Builder = apply { this.batchSize = batchSize }

        /** Resend SENT records not processed within this duration (lost messages). Default 1 h. */
        public fun redeliverAfter(redeliverAfter: Duration): Builder = apply { this.redeliverAfter = redeliverAfter }

        public fun build(): DomainEventBus = DomainEventBus(this)
    }
}
