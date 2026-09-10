package dev.nightjar.domainevent

import dev.nightjar.domainevent.spi.EventSerializer

class TestEvent @JvmOverloads constructor(
    val data: String,
    private val key: String? = null,
) : DomainEvent {

    override fun sequenceKey(): String? = key
}

object TestEventSerializer : EventSerializer {

    override fun serialize(event: DomainEvent): ByteArray = (event as TestEvent).data.toByteArray()

    override fun deserialize(type: String, payload: ByteArray): DomainEvent {
        require(type == "TestEvent") { "Unknown event type: $type" }
        return TestEvent(String(payload))
    }
}
