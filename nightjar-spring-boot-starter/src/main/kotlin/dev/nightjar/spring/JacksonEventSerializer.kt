package dev.nightjar.spring

import com.fasterxml.jackson.databind.ObjectMapper
import dev.nightjar.domainevent.DomainEvent
import dev.nightjar.domainevent.spi.EventSerializer

/**
 * Jackson-backed [EventSerializer] with an explicit allowlist of event
 * classes, keyed by simple class name (the [DomainEvent.eventType] default).
 *
 * The starter builds one automatically from the event classes of all
 * subscribed listeners. Define your own [EventSerializer] bean to take over —
 * required if you override `eventType()` on any event, or need Kotlin data
 * classes (add `jackson-module-kotlin` to your `ObjectMapper`).
 */
public class JacksonEventSerializer(
    private val objectMapper: ObjectMapper,
    eventClasses: Collection<Class<out DomainEvent>>,
) : EventSerializer {

    private val byType: Map<String, Class<out DomainEvent>>

    init {
        val duplicates = eventClasses.groupBy { it.simpleName }.filterValues { it.size > 1 }
        require(duplicates.isEmpty()) {
            "Event classes with colliding simple names need a custom EventSerializer: $duplicates"
        }
        byType = eventClasses.associateBy { it.simpleName }
    }

    override fun serialize(event: DomainEvent): ByteArray = objectMapper.writeValueAsBytes(event)

    override fun deserialize(type: String, payload: ByteArray): DomainEvent {
        val eventClass = byType[type] ?: throw IllegalArgumentException(
            "Unknown event type '$type' — not among the subscribed event classes ${byType.keys}",
        )
        return objectMapper.readValue(payload, eventClass)
    }
}
