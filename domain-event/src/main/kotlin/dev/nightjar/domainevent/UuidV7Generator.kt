package dev.nightjar.domainevent

import dev.nightjar.domainevent.spi.IdGenerator
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * UUIDv7 ids (RFC 9562): a 48-bit Unix millisecond timestamp followed by
 * random bits — globally unique and time-sortable, so outbox rows and logs
 * order naturally. No database round-trip, no coordination.
 */
public class UuidV7Generator @JvmOverloads constructor(
    private val clock: Clock = Clock.systemUTC(),
) : IdGenerator {

    private val random = SecureRandom()

    override fun nextId(): String {
        val randomBytes = ByteArray(10)
        random.nextBytes(randomBytes)

        val timestamp = clock.millis()
        var msb = (timestamp shl 16) // 48-bit timestamp into the top of the high word
        msb = msb or 0x7000L // version 7
        msb = msb or (randomBytes[0].toLong() and 0x0F shl 8) // rand_a (12 bits)
        msb = msb or (randomBytes[1].toLong() and 0xFF)

        var lsb = 0x80L shl 56 // variant 10xx
        lsb = lsb or (randomBytes[2].toLong() and 0x3F shl 56)
        for (i in 3..9) {
            lsb = lsb or (randomBytes[i].toLong() and 0xFF shl (8 * (9 - i)))
        }

        return UUID(msb, lsb).toString()
    }
}
