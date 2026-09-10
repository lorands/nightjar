package dev.nightjar.spring

import java.time.Instant

/**
 * A Spring bean implementing this is auto-registered with the
 * [dev.nightjar.coordination.scheduler.SimpleScheduler] under [jobType] —
 * the starter's way of declaring scheduled jobs:
 *
 * ```kotlin
 * @Component
 * class PaymentReminderJob(private val mailer: Mailer) : ScheduledJobBean {
 *     override fun jobType() = "payment-reminder"
 *     override fun run(aggregateId: String, context: String?): Instant? {
 *         mailer.remind(aggregateId)
 *         return null // or the next fire time to repeat
 *     }
 * }
 * ```
 *
 * Execution is at-least-once: implementations must be idempotent.
 */
public interface ScheduledJobBean {

    public fun jobType(): String

    public fun run(aggregateId: String, context: String?): Instant?
}
