package dev.nightjar.domainevent

import dev.nightjar.domainevent.spi.RetryPolicy
import java.time.Duration
import java.time.Instant

/**
 * Time-windowed retry: young records retry quickly, older ones slowly, and
 * after [maxAge] the record is suspended.
 *
 * The defaults: every 20 seconds within the first 5 minutes of a record's
 * life, every 5 minutes after that, giving up after 1 hour.
 */
public class TimeWindowRetryPolicy @JvmOverloads constructor(
    private val youngInterval: Duration = Duration.ofSeconds(20),
    private val youngWindow: Duration = Duration.ofMinutes(5),
    private val matureInterval: Duration = Duration.ofMinutes(5),
    private val maxAge: Duration = Duration.ofHours(1),
) : RetryPolicy {

    init {
        require(!youngInterval.isNegative && !youngInterval.isZero) { "youngInterval must be positive" }
        require(!matureInterval.isNegative && !matureInterval.isZero) { "matureInterval must be positive" }
    }

    override fun isRetryDue(createdAt: Instant, lastAttemptAt: Instant, now: Instant): Boolean {
        val interval = if (Duration.between(createdAt, now) < youngWindow) youngInterval else matureInterval
        return Duration.between(lastAttemptAt, now) >= interval
    }

    override fun isExhausted(createdAt: Instant, now: Instant): Boolean =
        Duration.between(createdAt, now) >= maxAge
}
