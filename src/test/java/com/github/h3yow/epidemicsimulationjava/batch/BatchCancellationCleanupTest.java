package com.github.h3yow.epidemicsimulationjava.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.epidemicsimulationjava.capacity.CapacityGuard;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Deterministic reproduction of the cancellation-cleanup contract:
 * the instant {@link BatchRunner#run} reports cancellation, every background
 * computation thread of that batch must already be dead, and the distinction
 * between cancellation and timeout must survive a caller that is already
 * interrupted. A controllable replication task (interrupt-aware, but with a
 * small, bounded shutdown latency) stands in for the real engine.
 */
class BatchCancellationCleanupTest {


    private static final long JOIN_TIMEOUT_MILLIS = 30_000L;
    private static final long TASK_SHUTDOWN_LATENCY_MILLIS = 250L;
    private static final long STARTUP_GRACE_MILLIS = 1_000L;

    private static ScenarioConfig scenario() {
        return new ScenarioConfig(1_000, 5, 0.3, 0.08, 100, List.of());
    }

    /**
     * A replication task that:
     * <ul>
     *   <li>signals {@code started} once running and registers its thread,</li>
     *   <li>blocks until all observed tasks are running, then keeps working,</li>
     *   <li>reacts to interruption but only exits after a fixed shutdown latency,
     *       simulating a worker between interruption check points.</li>
     * </ul>
     */
    static final class ControllableTask {
        private final int parallelism;
        private final CountDownLatch started;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final List<Thread> threads =
                java.util.Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger inFlight = new AtomicInteger();
        private volatile long shutdownLatencyMillis = TASK_SHUTDOWN_LATENCY_MILLIS;

        ControllableTask(int parallelism) {
            this.parallelism = parallelism;
            this.started = new CountDownLatch(parallelism);
        }

        ReplicationExecutor executor() {
            return (scenario, seed, index) -> {
                Thread self = Thread.currentThread();
                threads.add(self);
                inFlight.incrementAndGet();
                started.countDown();
                try {
                    if (!started.await(STARTUP_GRACE_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        throw new AssertionError("not all replications started");
                    }
                    // Work until interrupted. The sleep is uninterruptible: it
                    // deliberately ignores InterruptedException once, so the
                    // shutdown latency is real and bounded.
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            long stopAt = System.currentTimeMillis() + shutdownLatencyMillis;
                            long remaining;
                            while ((remaining = stopAt - System.currentTimeMillis()) > 0) {
                                try {
                                    Thread.sleep(remaining);
                                } catch (InterruptedException again) {
                                    // keep ignoring: bounded shutdown latency
                                }
                            }
                            Thread.currentThread().interrupt();
                            throw new java.util.concurrent.CancellationException(
                                    "replication " + index + " interrupted");
                        }
                    }
                    throw new java.util.concurrent.CancellationException(
                            "replication " + index + " interrupted");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException(
                            "replication " + index + " interrupted while starting");
                } finally {
                    inFlight.decrementAndGet();
                    if (inFlight.get() == 0) {
                        finished.countDown();
                    }
                }
            };
        }

        void awaitStarted() throws InterruptedException {
            assertTrue(started.await(STARTUP_GRACE_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS),
                    "replications did not start in time");
        }

        /** @return live registered worker threads at this instant */
        List<Thread> liveThreadsSnapshot() {
            synchronized (threads) {
                List<Thread> live = new ArrayList<>();
                for (Thread t : threads) {
                    if (t.isAlive()) {
                        live.add(t);
                    }
                }
                return live;
            }
        }

        int inFlightCount() {
            return inFlight.get();
        }
    }

    /**
     * Core reproduction: parallel batch interrupted mid-flight. At the instant
     * run() returns with cancellation, no worker thread of the batch may be
     * alive and no task may still be in flight.
     */
    @Test
    void parallelCancellationReturnsOnlyAfterEveryWorkerThreadIsDead() throws Exception {
        int parallelism = 8;
        ControllableTask task = new ControllableTask(parallelism);
        BatchRunner runner = new BatchRunner(new CapacityGuard(), task.executor());
        BatchRequest request = new BatchRequest(scenario(), 40, 7L, parallelism, 0);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                runner.run(request);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                Thread.interrupted();
            }
        }, "cancelling-caller");
        caller.start();

        // Cancel only once every observed replication is actually running.
        task.awaitStarted();
        caller.interrupt();
        caller.join(JOIN_TIMEOUT_MILLIS);

        assertFalse(caller.isAlive(), "run() did not return after cancellation");
        Throwable t = failure.get();
        assertTrue(t instanceof BatchCancelledException,
                "expected BatchCancelledException, got " + t);
        assertFalse(((BatchCancelledException) t).isTimeout(),
                "explicit interrupt must be reported as cancellation, not timeout");

        // Strict, zero-grace assertions evaluated at the instant run() has returned.
        List<Thread> liveAtReturn = task.liveThreadsSnapshot();
        assertTrue(liveAtReturn.isEmpty(),
                "worker threads still alive when cancellation was reported: " + liveAtReturn);
        assertEquals(0, task.inFlightCount(),
                "replication tasks still in flight when cancellation was reported");
    }

    /**
     * Repeated cancellations must not accumulate worker threads or leak state:
     * cancel many heavy batches back-to-back, then a normal batch must still
     * succeed on the same runner instance.
     */
    @Test
    void repeatedParallelCancellationsLeaveNoResidue() throws Exception {
        int parallelism = 4;
        for (int round0 = 0; round0 < 5; round0++) {
            final int round = round0;
            ControllableTask task = new ControllableTask(parallelism);
            BatchRunner roundRunner = new BatchRunner(new CapacityGuard(), task.executor());
            BatchRequest request = new BatchRequest(scenario(), 16, round + 100L, parallelism, 0);

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    roundRunner.run(request);
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    Thread.interrupted();
                }
            }, "cancelling-caller-" + round);
            caller.start();

            task.awaitStarted();
            caller.interrupt();
            caller.join(JOIN_TIMEOUT_MILLIS);
            assertFalse(caller.isAlive(), "round " + round + " caller hung");
            Throwable t = failure.get();
            assertTrue(t instanceof BatchCancelledException,
                    "round " + round + ": expected BatchCancelledException, got " + t);
            assertFalse(((BatchCancelledException) t).isTimeout(),
                    "round " + round + ": cancellation misreported as timeout");
            assertTrue(task.liveThreadsSnapshot().isEmpty(),
                    "round " + round + ": live workers after cancellation");
            assertEquals(0, task.inFlightCount(), "round " + round + ": tasks still in flight");
        }

        // A fresh normal batch on a new runner must still succeed after all the
        // cancellations, with deterministic (non-partial) results.
        BatchRunner realRunner = new BatchRunner();
        AggregateResult first = realRunner.run(new BatchRequest(scenario(), 10, 5L, 3, 0));
        AggregateResult second = realRunner.run(new BatchRequest(scenario(), 10, 5L, 1, 0));
        assertEquals(10, first.replications());
        assertEquals(first.meanPeakInfected(), second.meanPeakInfected(), 0.0);
    }

    /**
     * Caller thread already carries an interrupt signal when run() is invoked:
     * this must still be reported as cancellation (never timeout, even though
     * the request also carries a 1ms timeout) and the interrupt status must be
     * preserved on the caller.
     */
    @Test
    void enteringWithInterruptIsCancellationNotTimeout() throws Exception {
        int parallelism = 4;
        ControllableTask task = new ControllableTask(parallelism);
        BatchRunner runner = new BatchRunner(new CapacityGuard(), task.executor());
        BatchRequest request = new BatchRequest(scenario(), 16, 9L, parallelism, 1);

        AtomicReference<BatchCancelledException> failure = new AtomicReference<>();
        AtomicBoolean interruptedState = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                runner.run(request);
            } catch (BatchCancelledException e) {
                failure.set(e);
                interruptedState.set(Thread.currentThread().isInterrupted());
            }
        }, "pre-interrupted-caller");
        long start = System.currentTimeMillis();
        caller.start();
        caller.join(JOIN_TIMEOUT_MILLIS);
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(caller.isAlive(), "run() did not return for a pre-interrupted caller");

        BatchCancelledException e = failure.get();
        assertTrue(e != null, "expected BatchCancelledException");
        assertFalse(e.isTimeout(),
                "a pre-existing interrupt must be reported as cancellation, not timeout");
        assertTrue(interruptedState.get(),
                "the caller's interrupt status must remain set after cancellation");
        assertTrue(elapsed < STARTUP_GRACE_MILLIS,
                "a pre-interrupted call must fail before scheduling work, took " + elapsed + "ms");
        assertTrue(task.liveThreadsSnapshot().isEmpty(),
                "no worker may linger after a pre-interrupted call");
    }

    /**
     * When both a timeout elapses and an interrupt is present, cancellation
     * wins (the caller asked to stop); this must hold on the serial path too.
     */
    @Test
    void interruptWinsOverTimeoutOnSerialPath() {
        BatchRunner runner = new BatchRunner();
        BatchRequest request = new BatchRequest(
                new ScenarioConfig(50_000, 20, 0.3, 0.05, 20_000, List.of()),
                100, 3L, 1, 1);
        AtomicReference<BatchCancelledException> failure = new AtomicReference<>();
        AtomicBoolean interruptedState = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                runner.run(request);
            } catch (BatchCancelledException e) {
                failure.set(e);
                interruptedState.set(Thread.currentThread().isInterrupted());
            }
        }, "serial-pre-interrupted-caller");
        caller.start();
        try {
            caller.join(JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertFalse(caller.isAlive());
        BatchCancelledException e = failure.get();
        assertTrue(e != null, "expected BatchCancelledException on serial path");
        assertFalse(e.isTimeout(), "interrupt must not be misreported as timeout (serial)");
        assertTrue(interruptedState.get(), "interrupt status must remain set (serial)");
    }

    /**
     * A plain timeout (no caller interrupt) must be reported as timeout and must
     * NOT leave the calling thread interrupted — otherwise the very next normal
     * batch on that thread would be poisoned into "cancelled before it started".
     */
    @Test
    void timeoutDoesNotPoisonCallerInterruptStatus() throws Exception {
        BatchRunner runner = new BatchRunner();
        ScenarioConfig heavy = new ScenarioConfig(50_000, 20, 0.3, 0.05, 20_000, List.of());
        BatchRequest timingOut = new BatchRequest(heavy, 100, 4L, 4, 1);

        AtomicReference<BatchCancelledException> failure = new AtomicReference<>();
        AtomicBoolean interruptedAfterTimeout = new AtomicBoolean(true);
        AtomicReference<AggregateResult> next = new AtomicReference<>();
        AtomicReference<Throwable> nextFailure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                runner.run(timingOut);
            } catch (BatchCancelledException e) {
                failure.set(e);
                interruptedAfterTimeout.set(Thread.currentThread().isInterrupted());
                // Immediately reuse the same thread for a healthy batch: it must
                // not be affected by the timeout that just happened.
                try {
                    next.set(runner.run(new BatchRequest(scenario(), 10, 5L, 2, 0)));
                } catch (Throwable t) {
                    nextFailure.set(t);
                }
            } finally {
                Thread.interrupted();
            }
        }, "timeout-then-reuse-caller");
        caller.start();
        caller.join(JOIN_TIMEOUT_MILLIS);
        assertFalse(caller.isAlive(), "caller hung after timeout");

        BatchCancelledException e = failure.get();
        assertTrue(e != null, "expected BatchCancelledException");
        assertTrue(e.isTimeout(), "expected a timeout, got: " + e);
        assertFalse(interruptedAfterTimeout.get(),
                "timeout must not set the caller's interrupt status");
        assertTrue(nextFailure.get() == null,
                "next normal batch after timeout must not fail, got: " + nextFailure.get());
        assertEquals(10, next.get().replications());
    }

    /**
     * Parallel path: when the deadline has elapsed and the caller is interrupted
     * at the same time, the interrupt (cancellation) must win over the timeout.
     */
    @Test
    void interruptWinsOverTimeoutOnParallelPath() {
        BatchRunner runner = new BatchRunner();
        BatchRequest request = new BatchRequest(
                new ScenarioConfig(50_000, 20, 0.3, 0.05, 20_000, List.of()),
                100, 6L, 4, 1);
        AtomicReference<BatchCancelledException> failure = new AtomicReference<>();
        AtomicBoolean interruptedState = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                runner.run(request);
            } catch (BatchCancelledException e) {
                failure.set(e);
                interruptedState.set(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }, "parallel-pre-interrupted-caller");
        caller.start();
        try {
            caller.join(JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertFalse(caller.isAlive());
        BatchCancelledException e = failure.get();
        assertTrue(e != null, "expected BatchCancelledException on parallel path");
        assertFalse(e.isTimeout(), "interrupt must not be misreported as timeout (parallel)");
        assertTrue(interruptedState.get(), "interrupt status must remain set (parallel)");
    }
}
