package dev.nightjar.domainevent.spi

/**
 * Cluster-wide stop switch for event delivery and processing.
 *
 * Checked by the outbox relay before every poll pass and by consumers before
 * claiming an incoming message. While closed, the relay sends nothing and
 * consumers drop messages unclaimed — records keep their state (nothing is
 * suspended or lost), so reopening the gate resumes delivery automatically on
 * the next poll. Messages already in flight when the gate closed are re-sent
 * by the relay after `redeliverAfter`.
 *
 * Back the gate with any shared signal — the worker pool's
 * `isSuspended(type)` (a database-driven switch all instances see), a feature
 * flag, a config service. The default is [OPEN].
 */
public fun interface ProcessingGate {

    /** `true` when events may be delivered and processed. */
    public fun isOpen(): Boolean

    public companion object {

        /** Always open: no cluster-wide stop wired. */
        @JvmField
        public val OPEN: ProcessingGate = ProcessingGate { true }
    }
}
