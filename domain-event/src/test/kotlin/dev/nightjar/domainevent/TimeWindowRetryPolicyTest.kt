package dev.nightjar.domainevent

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeWindowRetryPolicyTest {

    private val policy = TimeWindowRetryPolicy() // defaults: 20s / 5min / 5min / 1h
    private val created: Instant = Instant.parse("2026-01-01T10:00:00Z")

    @Test
    fun `young record retries every 20 seconds`() {
        val lastAttempt = created.plusSeconds(60)
        assertFalse(policy.isRetryDue(created, lastAttempt, lastAttempt.plusSeconds(10)))
        assertTrue(policy.isRetryDue(created, lastAttempt, lastAttempt.plusSeconds(20)))
    }

    @Test
    fun `mature record retries every 5 minutes`() {
        val lastAttempt = created.plus(Duration.ofMinutes(10))
        assertFalse(policy.isRetryDue(created, lastAttempt, lastAttempt.plusSeconds(60)))
        assertTrue(policy.isRetryDue(created, lastAttempt, lastAttempt.plus(Duration.ofMinutes(5))))
    }

    @Test
    fun `boundary - record exactly at young window edge uses mature interval`() {
        val lastAttempt = created.plus(Duration.ofMinutes(5)) // youngWindow elapsed
        assertFalse(policy.isRetryDue(created, lastAttempt, lastAttempt.plusSeconds(20)))
        assertTrue(policy.isRetryDue(created, lastAttempt, lastAttempt.plus(Duration.ofMinutes(5))))
    }

    @Test
    fun `exhausted after max age`() {
        assertFalse(policy.isExhausted(created, created.plus(Duration.ofMinutes(59))))
        assertTrue(policy.isExhausted(created, created.plus(Duration.ofHours(1))))
    }

    @Test
    fun `rejects non-positive intervals`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            TimeWindowRetryPolicy(youngInterval = Duration.ZERO)
        }
    }
}
