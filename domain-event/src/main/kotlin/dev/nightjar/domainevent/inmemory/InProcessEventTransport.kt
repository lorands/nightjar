package dev.nightjar.domainevent.inmemory

import dev.nightjar.domainevent.spi.EventTransport
import dev.nightjar.domainevent.spi.TransportMessage
import dev.nightjar.domainevent.spi.TransportMessageHandler
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process [EventTransport]: a single daemon thread delivers messages in
 * send order. For tests and single-instance applications — no broker needed,
 * async listener semantics preserved.
 */
public class InProcessEventTransport : EventTransport {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nightjar-inprocess-transport").apply { isDaemon = true }
    }
    private val handler = AtomicReference<TransportMessageHandler?>()
    private val beforeStartBuffer = ConcurrentLinkedQueue<TransportMessage>()

    override fun send(message: TransportMessage) {
        executor.execute {
            val current = handler.get()
            if (current != null) current.onMessage(message) else beforeStartBuffer.add(message)
        }
    }

    override fun startConsuming(handler: TransportMessageHandler) {
        check(this.handler.compareAndSet(null, handler)) { "startConsuming may be called only once" }
        executor.execute {
            while (true) {
                val buffered = beforeStartBuffer.poll() ?: break
                handler.onMessage(buffered)
            }
        }
    }

    override fun close() {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }
}
