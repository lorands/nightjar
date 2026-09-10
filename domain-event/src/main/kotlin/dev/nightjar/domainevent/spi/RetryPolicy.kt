package dev.nightjar.domainevent.spi

import java.time.Instant

/**
 * Decides when failed records are retried and when to give up.
 * Evaluated by the relay against the outbox record's timestamps.
 */
public interface RetryPolicy {

    /** Should a record that failed at [lastAttemptAt] be retried now? */
    public fun isRetryDue(createdAt: Instant, lastAttemptAt: Instant, now: Instant): Boolean

    /** Has the record's total retry window elapsed? If so it gets suspended. */
    public fun isExhausted(createdAt: Instant, now: Instant): Boolean
}
