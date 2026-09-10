package dev.nightjar.migrations.data

/**
 * Processes one batch of entity ids enqueued for data migration under a
 * registered type.
 *
 * Delivery is at-least-once: a failing batch is released and retried on a
 * later poll, so implementations must be idempotent. Throwing marks the whole
 * batch failed.
 */
public fun interface DataMigrationHandler {

    public fun process(type: String, ids: List<String>)
}
