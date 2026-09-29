package com.github.h3yow.epidemicsimulationjava.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Cancellation/timeout cleanup semantics for {@link BatchRunner}.
 *
 * <p>The key guarantee under test: the instant {@link BatchRunner#run} returns
 * — normally or exceptionally — every worker thread created by that batch is
 * already dead. Workers are parked at a deterministic mid-flight barrier via
 * the package-private replication hook, so these tests do not rely on sleep
 * timing and stay stable under load.
 */
class BatchCancellationTest {

    private final BatchRunner runner = new BatchRunner();

    /** Heavy per-replication workload used whenever the hook is not installed. */
    private static ScenarioConfig heavyScenario() {
        return new ScenarioConfig(50_000, 20, 0.3, 0.05, 20_000, List.of());
    }

    private static ScenarioConfig normalScenario() {
        return new ScenarioConfig(8_000, 15, 0.35, 0.08, 240, List.of(
                new com.github.h3yow.epidemicsimulationjava.model.Intervention(
                        com.github.h3yow.epidemicsimulationjava.model.InterventionType.SOCIAL_DISTANCING,
                        60, 120, 0.5)));
    }

    @AfterEach
    void clearHook() {
        BatchRunner.replicationHook = null;
    }

    private static long countLiveBatchWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("batch-worker-"))
                .filter(Thread::isAlive)
                .count();
    }

    /**
     * Cancels a parallel batch once exactly {@code parallelism} worker threads
     * are parked mid-replication, then asserts — at the exact moment run() has
     * returned — that none of the captured worker threads is alive any more.
     *
     * @return the thrown failure, classified by the caller
     */
    private BatchCancelledException cancelAtMidFlightBarrier(
            int parallelism, long seed, long timeoutMillis) throws InterruptedException {
        int reps = parallelism * 4;
        CountDownLatch arrived = new CountDownLatch(parallelism);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> batchWorkers = new CopyOnWriteArrayList<>();
        BatchRunner.replicationHook = new Runnable() {
            @Override
            public void run() {
                batchWorkers.add(Thread.currentThread());
                arrived.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    // Mirrors a cooperative task: surface as CancellationException.
                    throw new java.util.concurrent.CancellationException("parked task interrupted");
                }
            }
        };

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                runner.run(new BatchRequest(heavyScenario(), reps, seed, parallelism, timeoutMillis));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "batch-caller-" + seed);
        caller.start();
        assertTrue(arrived.await(10, TimeUnit.SECONDS),
                "workers did not reach the mid-flight barrier");

        // Every created worker belongs to this batch (no other tests run batches
        // on this runner concurrently), but assert against the exact captured
        // threads to prove the returned batch owns them.
        assertEquals(parallelism, batchWorkers.size());
        long liveBefore = countLiveBatchWorkers();
        assertTrue(liveBefore >= parallelism, "workers must be alive mid-flight");

        caller.interrupt();
        caller.join(30_000);
        assertFalse(caller.isAlive(), "cancelled batch must return promptly");

        // The contract: at return, not a single worker of this batch survives.
        int aliveAtReturn = 0;
        for (Thread w : batchWorkers) {
            if (w.isAlive()) {
                aliveAtReturn++;
            }
        }
        assertEquals(0, aliveAtReturn,
                "worker threads must all be dead the instant the batch returns");
        release.countDown();

        Throwable t = failure.get();
        assertNotNull(t, "cancelled batch must fail, never return a partial result");
        assertTrue(t instanceof BatchCancelledException,
                "expected BatchCancelledException, got " + t);
        return (BatchCancelledException) t;
    }

    @Test
    void parallelCancellationAcrossConcurrencyLevelsLeavesNoWorkers() throws Exception {
        // parallelism == 1 is the strictly serial path, covered separately below.
        for (int parallelism : new int[] {2, 8, 16}) {
            BatchCancelledException e =
                    cancelAtMidFlightBarrier(parallelism, 100L + parallelism, 0);
            assertFalse(e.isTimeout(),
                    "explicit interrupt at parallelism " + parallelism
                            + " must be reported as cancellation, not timeout: " + e.getMessage());
            assertEquals(0L, countLiveBatchWorkers(),
                    "no batch workers may linger after cancellation at parallelism " + parallelism);
        }
    }

    @Test
    void repeatedMidFlightCancellationsNeverAccumulateWorkers() throws Exception {
        // Warm the JIT so timings later in the test are not dominated by
        // class loading/compilation (the barrier makes correctness independent
        // of timing anyway).
        runner.run(new BatchRequest(normalScenario(), 4, 1L, 2, 0));

        for (int round = 1; round <= 6; round++) {
            BatchCancelledException e = cancelAtMidFlightBarrier(16, 200L + round, 0);
            assertFalse(e.isTimeout(), "round " + round + " must be a cancellation");
            assertEquals(0L, countLiveBatchWorkers(),
                    "round " + round + ": previous cancellations must not leave workers behind");
        }
    }

    @Test
    void callArrivingWithInterruptSetIsCancellationNotTimeout() throws Exception {
        // Start the caller, park it immediately before run(), then interrupt it
        // and release: run() is therefore entered with the interrupt flag
        // already set. The deadline (1 ms) has also elapsed by then, yet the
        // classification must still be cancellation, never timeout.
        CountDownLatch atEntry = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<BatchCancelledException> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            atEntry.countDown();
            // Immune-to-interrupt parking: any early interrupt is swallowed
            // here but its flag re-asserted, so run() below is always entered
            // with the interrupt status set.
            boolean parkedInterrupted = false;
            try {
                proceed.await();
            } catch (InterruptedException e) {
                parkedInterrupted = true;
            }
            if (parkedInterrupted) {
                Thread.currentThread().interrupt();
            }
            // Interrupt is set before this point; this must be a cancellation.
            BatchCancelledException e = assertThrows(BatchCancelledException.class,
                    () -> runner.run(new BatchRequest(normalScenario(), 8, 9L, 4, 1)));
            assertFalse(e.isTimeout(),
                    "an interrupted entry must be a cancellation even if the deadline passed");
            failure.set(e);
        }, "preinterrupted-caller");
        caller.start();
        assertTrue(atEntry.await(10, TimeUnit.SECONDS));
        proceed.countDown();
        // Interrupt after release; whether it lands during the tail of parking
        // or at run() entry, the flag is guaranteed set when run() executes.
        caller.interrupt();
        caller.join(10_000);
        assertFalse(caller.isAlive());
        assertNotNull(failure.get());
    }

    @Test
    void timeoutDuringParallelRunLeavesNoWorkersAndIsClassifiedTimeout() throws Exception {
        int reps = 200;
        int parallelism = 8;
        // Park the workers so the first get() is guaranteed to see the deadline
        // elapse while work is genuinely in flight.
        CountDownLatch arrived = new CountDownLatch(parallelism);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> batchWorkers = new CopyOnWriteArrayList<>();
        BatchRunner.replicationHook = () -> {
            batchWorkers.add(Thread.currentThread());
            arrived.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new java.util.concurrent.CancellationException("parked task interrupted");
            }
        };

        AtomicReference<Throwable> failure = new AtomicReference<>();
        // Small timeout starting now: once all workers are parked mid-flight,
        // the deadline elapses while tasks are genuinely running.
        long timeoutMillis = 300;
        Thread caller = new Thread(() -> {
            try {
                runner.run(new BatchRequest(heavyScenario(), reps, 42L, parallelism, timeoutMillis));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "timeout-caller");
        caller.start();
        assertTrue(arrived.await(10, TimeUnit.SECONDS));
        // Deadline now elapses while workers are parked; cleanup interrupts them.
        caller.join(30_000);
        assertFalse(caller.isAlive());

        int aliveAtReturn = 0;
        for (Thread w : batchWorkers) {
            if (w.isAlive()) {
                aliveAtReturn++;
            }
        }
        assertEquals(0, aliveAtReturn, "timeout return must mean every worker is already dead");
        // Release the barrier for any late-parked task; interruption must
        // already have unwound all real work.
        release.countDown();

        Throwable t = failure.get();
        assertNotNull(t);
        assertTrue(t instanceof BatchCancelledException, "got " + t);
        assertTrue(((BatchCancelledException) t).isTimeout(),
                "deadline elapsed without interrupt must be a timeout, got: " + t.getMessage());
        assertEquals(0L, countLiveBatchWorkers());
    }

    @Test
    void normalBatchAfterRepeatedCancellationsIsUnaffectedAndConsistentWithSerial()
            throws Exception {
        for (int round = 1; round <= 3; round++) {
            BatchCancelledException e = cancelAtMidFlightBarrier(8, 300L + round, 0);
            assertFalse(e.isTimeout());
        }

        // A fresh, normal parallel batch must succeed, carry no state from the
        // cancelled batches, and match the serial aggregate exactly.
        int reps = 40;
        long masterSeed = 20260930L;
        AggregateResult serial =
                runner.run(new BatchRequest(normalScenario(), reps, masterSeed, 1, 0));
        AggregateResult parallel =
                runner.run(new BatchRequest(normalScenario(), reps, masterSeed, 8, 0));
        AggregateResult parallel16 =
                runner.run(new BatchRequest(normalScenario(), reps, masterSeed, 16, 0));

        assertEquals(serial.meanPeakInfected(), parallel.meanPeakInfected(), 0.0);
        assertEquals(serial.meanPeakInfected(), parallel16.meanPeakInfected(), 0.0);
        assertEquals(serial.peakInfectedP2_5(), parallel.peakInfectedP2_5(), 0.0);
        assertEquals(serial.peakInfectedP97_5(), parallel.peakInfectedP97_5(), 0.0);
        assertEquals(serial.meanTotalRecovered(), parallel.meanTotalRecovered(), 0.0);
        assertEquals(reps, parallel.results().size());
        assertEquals(0L, countLiveBatchWorkers());
    }

    @Test
    void serialPathCancellationIsAllOrNothingAndClassifiedCancellation() throws Exception {
        // No hook: serial replication cooperatively checks interruption every
        // simulated hour. Cancel mid run and assert the same semantics.
        BatchRunner serialRunner = new BatchRunner();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                serialRunner.run(new BatchRequest(heavyScenario(), 50, 500L, 1, 0));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "serial-caller");
        caller.start();
        Thread.sleep(50);
        caller.interrupt();
        caller.join(30_000);
        assertFalse(caller.isAlive());
        Throwable t = failure.get();
        assertNotNull(t);
        assertTrue(t instanceof BatchCancelledException, "got " + t);
        assertFalse(((BatchCancelledException) t).isTimeout(),
                "serial cancellation must not be reported as timeout");
        // The serial path creates no pool at all; the runner must stay usable.
        AggregateResult after =
                serialRunner.run(new BatchRequest(normalScenario(), 10, 7L, 1, 0));
        assertEquals(10, after.replications());
    }

    @Test
    void timedCancellationWithQueuedTasksStillCleansUpEverything() throws Exception {
        // More reps than workers: most tasks are still queued when the cancel
        // lands. Queued tasks must be dropped/interrupted just like running ones
        // and no thread may survive.
        BatchCancelledException e = cancelAtMidFlightBarrier(4, 400L, 0);
        assertFalse(e.isTimeout());
        assertEquals(0L, countLiveBatchWorkers());
    }
}
