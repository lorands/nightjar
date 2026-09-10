package dev.nightjar.examples

import dev.nightjar.coordination.lock.InMemoryProcessLock
import dev.nightjar.coordination.scheduler.InMemorySchedulerStore
import dev.nightjar.coordination.scheduler.SimpleScheduler
import java.time.Duration
import java.time.Instant

/**
 * Persistent one-shot timers for aggregates: schedule a job at an absolute
 * time, let its return value reschedule it, cancel by identity.
 *
 * In production swap the in-memory store/lock for `JdbcSchedulerStore` +
 * `JdbcProcessLock` — then timers survive restarts, fire exactly once per due
 * time across all instances, and `schedule()` joins your transaction.
 */
object SchedulerExample {

    @JvmStatic
    fun main(args: Array<String>) {
        val scheduler = SimpleScheduler.builder()
            .store(InMemorySchedulerStore())
            .lock(InMemoryProcessLock())
            .pollInterval(Duration.ofMillis(50)) // demo; default 60 s
            .build()

        var reminders = 0
        scheduler.register("payment-reminder") { orderId, context ->
            reminders++
            println("[reminder $reminders] order $orderId (context=$context)")
            // return the next fire time to repeat, null when done
            if (reminders < 2) Instant.now().plusMillis(100) else null
        }

        scheduler.start()
        scheduler.use {
            it.schedule("order-42", Instant.now().plusMillis(100), "payment-reminder", """{"channel":"email"}""")
            Thread.sleep(600) // demo only: let the poller fire both reminders
        }
    }
}
