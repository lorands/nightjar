package dev.nightjar.coordination.scheduler

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Heap-backed [SchedulerStore] for tests and single-instance applications. */
public class InMemorySchedulerStore : SchedulerStore {

    private val entries = ConcurrentHashMap<Pair<String, String>, ScheduledEntry>()

    override fun upsert(entry: ScheduledEntry) {
        entries[entry.aggregateId to entry.jobType] = entry
    }

    override fun delete(aggregateId: String, jobType: String) {
        entries.remove(aggregateId to jobType)
    }

    override fun due(now: Instant, limit: Int): List<ScheduledEntry> =
        entries.values.asSequence()
            .filter { !it.fireAfter.isAfter(now) }
            .sortedBy { it.fireAfter }
            .take(limit)
            .toList()

    /** Pending entries — for assertions and inspection. */
    public fun size(): Int = entries.size
}
