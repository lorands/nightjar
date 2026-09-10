package dev.nightjar.coordination.scheduler

import java.time.Instant

/**
 * A job the [SimpleScheduler] fires when its time comes.
 *
 * @return the next fire time to reschedule this `(aggregateId, jobType)`
 *         entry, or `null` when the job is done. A thrown exception
 *         reschedules the entry after the configured retry delay
 *         (default 5 minutes).
 *
 * Execution is at-least-once across the cluster (exactly-once per fire
 * window): implementations should be idempotent.
 */
public fun interface ScheduledJob {

    public fun run(aggregateId: String, context: String?): Instant?
}
