package dev.nightjar.examples;

import dev.nightjar.coordination.lock.InMemoryProcessLock;
import dev.nightjar.coordination.lock.ProcessLock;
import dev.nightjar.coordination.worker.InMemoryWorkerPool;
import dev.nightjar.coordination.worker.WorkerPool;

import java.time.Duration;

/**
 * The two coordination primitives from Java:
 *
 * <ul>
 *   <li>{@link ProcessLock} — cross-instance named mutex with optional lease
 *       (a crashed holder's lock self-heals after the TTL)</li>
 *   <li>{@link WorkerPool} — cross-instance concurrency throttle;
 *       {@code configure(type, 0)} suspends a work type cluster-wide</li>
 * </ul>
 *
 * In production use {@code JdbcProcessLock} / {@code JdbcWorkerPool} — same
 * API, database-backed across instances.
 */
public final class LockAndWorkerExample {

    public static void main(String[] args) throws Exception {
        // --- process lock: only one invoice run at a time, lease heals crashes
        ProcessLock lock = new InMemoryProcessLock();
        boolean ran = lock.withLock("monthly-invoicing", Duration.ofMinutes(30), () ->
                System.out.println("[lock] generating invoices — exclusively"));
        System.out.println("[lock] ran=" + ran + ", second attempt while held would return false");

        // --- worker pool: at most 2 concurrent imports, cluster-wide
        try (WorkerPool pool = new InMemoryWorkerPool()) {
            pool.configure("catalog-import", 2);

            for (int i = 1; i <= 3; i++) {
                int run = i;
                boolean accepted = pool.execute("catalog-import", () ->
                        System.out.println("[worker] import " + run + " running"));
                if (!accepted) {
                    System.out.println("[worker] import " + run + " skipped — at capacity");
                }
            }

            pool.configure("catalog-import", 0); // operations: suspend the type
            System.out.println("[worker] suspended=" + pool.isSuspended("catalog-import"));
        }
    }

    private LockAndWorkerExample() {
    }
}
