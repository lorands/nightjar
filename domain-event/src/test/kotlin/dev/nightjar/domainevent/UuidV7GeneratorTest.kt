package dev.nightjar.domainevent

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UuidV7GeneratorTest {

    @Test
    fun `generates valid version 7 uuids`() {
        val id = UuidV7Generator().nextId()
        val uuid = UUID.fromString(id)
        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant()) // RFC 4122/9562 variant '10'
    }

    @Test
    fun `embeds the clock's timestamp in the top 48 bits`() {
        val instant = Instant.parse("2026-06-04T12:00:00Z")
        val id = UuidV7Generator(Clock.fixed(instant, ZoneOffset.UTC)).nextId()
        val msb = UUID.fromString(id).mostSignificantBits
        assertEquals(instant.toEpochMilli(), msb ushr 16)
    }

    @Test
    fun `ids are time-ordered across millis`() {
        val early = UuidV7Generator(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)).nextId()
        val late = UuidV7Generator(Clock.fixed(Instant.parse("2026-01-01T00:00:01Z"), ZoneOffset.UTC)).nextId()
        assertTrue(early < late)
    }

    @Test
    fun `ids are unique`() {
        val generator = UuidV7Generator()
        val ids = (1..10_000).map { generator.nextId() }.toSet()
        assertEquals(10_000, ids.size)
    }
}
