package dev.nightjar.spring

import com.fasterxml.jackson.databind.ObjectMapper
import dev.nightjar.domainevent.DomainEvent
import dev.nightjar.domainevent.DomainEventBus
import dev.nightjar.domainevent.DomainEventListener
import dev.nightjar.domainevent.DomainEventPublisher
import dev.nightjar.domainevent.SyncDomainEventListener
import dev.nightjar.domainevent.spi.EventSerializer
import dev.nightjar.domainevent.spi.EventTransport
import dev.nightjar.domainevent.spi.MetadataProvider
import dev.nightjar.domainevent.spi.OutboxStore
import dev.nightjar.domainevent.spi.ProcessingGate
import dev.nightjar.domainevent.spi.TransactionalRunner
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.core.ResolvableType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.ClassUtils

/**
 * Assembles, starts and stops the [DomainEventBus] as part of the Spring
 * lifecycle, and acts as the injectable [DomainEventPublisher].
 *
 * Assembly happens in [start] — after every bean exists — which is what lets
 * listeners inject the publisher without a definition cycle: listener beans
 * are resolved lazily, their event types derived via [ResolvableType]
 * (proxy-aware), subscriptions made in `@Order` order, and the Jackson
 * serializer's allowlist built from exactly those event classes.
 *
 * [publish] runs under `PROPAGATION_REQUIRED`: inside `@Transactional` callers
 * it joins their transaction; outside one it opens its own, so sync listeners
 * and the outbox append stay atomic even for bare calls. Without a
 * [PlatformTransactionManager] bean
 * (DataSource-less applications) it publishes directly.
 */
public class NightjarDomainEvents(
    private val store: OutboxStore,
    private val transport: EventTransport,
    private val serializerProvider: ObjectProvider<EventSerializer>,
    private val objectMapperProvider: ObjectProvider<ObjectMapper>,
    private val transactionalRunnerProvider: ObjectProvider<TransactionalRunner>,
    private val transactionManagerProvider: ObjectProvider<PlatformTransactionManager>,
    private val processingGateProvider: ObjectProvider<ProcessingGate>,
    private val metadataProviderProvider: ObjectProvider<MetadataProvider>,
    private val listeners: ObjectProvider<DomainEventListener<*>>,
    private val syncListeners: ObjectProvider<SyncDomainEventListener<*>>,
    private val properties: NightjarProperties,
    private val beanFactory: ConfigurableListableBeanFactory,
) : SmartLifecycle, DomainEventPublisher {

    @Volatile
    private var bus: DomainEventBus? = null

    @Volatile
    private var publishTemplate: TransactionTemplate? = null

    override fun publish(event: DomainEvent) {
        val current = checkNotNull(bus) {
            "The nightjar DomainEventBus is not running (publishing before context start or after shutdown?)"
        }
        val template = publishTemplate
        if (template != null) {
            template.executeWithoutResult { current.publish(event) }
        } else {
            current.publish(event)
        }
    }

    override fun start() {
        // REQUIRED, not REQUIRES_NEW: join an active business transaction,
        // open one only for bare (non-@Transactional) publish calls
        publishTemplate = transactionManagerProvider.ifAvailable?.let { TransactionTemplate(it) }

        val asyncListeners = listeners.orderedStream().toList()
        val syncListenerBeans = syncListeners.orderedStream().toList()

        val eventClasses = buildSet {
            asyncListeners.forEach { add(eventClassOf(it, DomainEventListener::class.java)) }
            syncListenerBeans.forEach { add(eventClassOf(it, SyncDomainEventListener::class.java)) }
        }

        val builder = DomainEventBus.builder()
            .serializer(serializer(eventClasses))
            .store(store)
            .transport(transport)
            .pollInterval(properties.domainEvent.pollInterval)
            .batchSize(properties.domainEvent.batchSize)
            .redeliverAfter(properties.domainEvent.redeliverAfter)
        transactionalRunnerProvider.ifAvailable { builder.transactionalRunner(it) }
        processingGateProvider.ifAvailable { builder.processingGate(it) }
        metadataProviderProvider.ifAvailable { builder.metadataProvider(it) }

        val assembled = builder.build()
        // orderedStream() already sorted by @Order — the index preserves it
        asyncListeners.forEachIndexed { index, listener ->
            @Suppress("UNCHECKED_CAST")
            assembled.subscribe(
                eventClassOf(listener, DomainEventListener::class.java) as Class<DomainEvent>,
                index,
                listener as DomainEventListener<DomainEvent>,
            )
        }
        syncListenerBeans.forEachIndexed { index, listener ->
            @Suppress("UNCHECKED_CAST")
            assembled.subscribeSync(
                eventClassOf(listener, SyncDomainEventListener::class.java) as Class<DomainEvent>,
                index,
                listener as SyncDomainEventListener<DomainEvent>,
            )
        }

        assembled.start()
        bus = assembled
    }

    override fun stop() {
        bus?.close()
        bus = null
        publishTemplate = null
    }

    override fun isRunning(): Boolean = bus != null

    private fun serializer(eventClasses: Set<Class<out DomainEvent>>): EventSerializer =
        serializerProvider.ifAvailable
            ?: JacksonEventSerializer(
                objectMapperProvider.ifAvailable ?: ObjectMapper().findAndRegisterModules(),
                eventClasses,
            )

    private fun eventClassOf(listener: Any, listenerInterface: Class<*>): Class<out DomainEvent> {
        // Try the instance class first (concrete listener classes)...
        val userClass = ClassUtils.getUserClass(listener)
        val resolved = ResolvableType.forClass(userClass).`as`(listenerInterface).getGeneric(0).resolve()
            // an unresolved type variable falls back to its bound (DomainEvent itself);
            // SAM lambdas land here — the generic then lives only in the @Bean
            // method's return type, i.e. the bean definition
            .takeUnless { it == null || it == DomainEvent::class.java }
            ?: beanDefinitionEventClass(listener, listenerInterface)
        checkNotNull(resolved) {
            "Cannot resolve the event type of ${userClass.name} — implement " +
                "${listenerInterface.simpleName}<YourEvent> with a concrete type argument " +
                "(or declare it as the @Bean method's return type)"
        }
        require(DomainEvent::class.java.isAssignableFrom(resolved)) {
            "${userClass.name} resolves to ${resolved.name}, which is not a DomainEvent"
        }
        @Suppress("UNCHECKED_CAST")
        return resolved as Class<out DomainEvent>
    }

    private fun beanDefinitionEventClass(listener: Any, listenerInterface: Class<*>): Class<*>? =
        beanFactory.getBeanNamesForType(listenerInterface).asSequence()
            .filter { beanFactory.containsBeanDefinition(it) }
            .filter { beanFactory.getSingleton(it) === listener }
            .mapNotNull {
                beanFactory.getMergedBeanDefinition(it).resolvableType
                    .`as`(listenerInterface).getGeneric(0).resolve()
            }
            .firstOrNull()
}
