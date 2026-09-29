package com.github.h3yow.epidemicsimulationjava.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.epidemicsimulationjava.model.Intervention;
import com.github.h3yow.epidemicsimulationjava.model.InterventionType;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import com.github.h3yow.epidemicsimulationjava.simulation.ReplicationResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Batch guarantees: identical aggregates across parallelism levels for the same
 * seed; cancellation/timeout is all-or-nothing and leaves no running workers.
 */
class BatchRunnerTest {

    private static final Logger log = LoggerFactory.getLogger(BatchRunnerTest.class);

    private final BatchRunner runner = new BatchRunner();

    private static ScenarioConfig scenario() {
        return new ScenarioConfig(8_000, 15, 0.35, 0.08, 240, List.of(
                new Intervention(InterventionType.SOCIAL_DISTANCING, 60, 120, 0.5),
                new Intervention(InterventionType.MASKING, 90, 180, 0.8)));
    }

    private static long countBatchWorkerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("batch-worker") && t.isAlive())
                .count();
    }

    /**
     * Blocks until batch worker threads are actually running, so a cancellation
     * test interrupts mid-flight rather than racing a batch that already
     * finished under a loaded machine.
     */
    private static void awaitBatchWorkersRunning() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (countBatchWorkerThreads() == 0) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("batch workers never started");
            }
            Thread.sleep(2);
        }
    }

    /**
     * Asserts no worker keeps running after a batch. Polls for a short grace
     * period to ride out the JVM's transient thread-enumeration lag, where a
     * thread that has already been joined (state TERMINATED) can still appear
     * in {@link Thread#getAllStackTraces()} for an instant. Only workers that
     * stay alive across the whole grace period count as leaked background work.
     */
    private static void assertNoLiveBatchWorkers(long baseline) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (true) {
            long live = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().startsWith("batch-worker"))
                    .filter(Thread::isAlive)
                    .filter(t -> t.getState() != Thread.State.TERMINATED)
                    .count();
            if (live <= baseline || System.currentTimeMillis() >= deadline) {
                org.junit.jupiter.api.Assertions.assertEquals(baseline, live,
                        "live worker threads must not survive the batch");
                return;
            }
            Thread.sleep(10);
        }
    }

    @Test
    void sameSeedSerialAndParallelGiveIdenticalResults() {
        long masterSeed = 20260922L;
        int reps = 40;
        AggregateResult serial = runner.run(new BatchRequest(scenario(), reps, masterSeed, 1, 0));
        AggregateResult parallel = runner.run(new BatchRequest(scenario(), reps, masterSeed, 4, 0));
        AggregateResult parallel8 = runner.run(new BatchRequest(scenario(), reps, masterSeed, 8, 0));

        log.info("consistency check: seed={} serial(meanPeak={}) parallel4(meanPeak={}) parallel8(meanPeak={})",
                masterSeed, serial.meanPeakInfected(), parallel.meanPeakInfected(),
                parallel8.meanPeakInfected());

        assertEquals(serial.meanPeakInfected(), parallel.meanPeakInfected(), 0.0);
        assertEquals(serial.peakInfectedP2_5(), parallel.peakInfectedP2_5(), 0.0);
        assertEquals(serial.peakInfectedP97_5(), parallel.peakInfectedP97_5(), 0.0);
        assertEquals(serial.meanTotalRecovered(), parallel.meanTotalRecovered(), 0.0);
        assertEquals(serial.meanPeakInfected(), parallel8.meanPeakInfected(), 0.0);

        for (int k = 0; k < reps; k++) {
            ReplicationResult s = serial.results().get(k);
            ReplicationResult p = parallel.results().get(k);
            assertEquals(s.seed(), p.seed(), "per-replication seed must not depend on parallelism");
            assertEquals(s.peakInfected(), p.peakInfected());
            org.junit.jupiter.api.Assertions.assertArrayEquals(s.hourlyInfected(), p.hourlyInfected());
        }
    }

    @Test
    void repeatedRunsWithSameSeedAndParallelismAreIdentical() {
        BatchRequest request = new BatchRequest(scenario(), 20, 99L, 4, 0);
        AggregateResult a = runner.run(request);
        AggregateResult b = runner.run(request);
        assertEquals(a.meanPeakInfected(), b.meanPeakInfected(), 0.0);
        assertEquals(a.meanTotalRecovered(), b.meanTotalRecovered(), 0.0);
    }

    @Test
    void timeoutAbortsWholeBatchAndLeavesNoWorkers() throws Exception {
        long workersBefore = countBatchWorkerThreads();
        // Heavy request with a 1ms timeout: one replication alone takes ~16ms,
        // so the deadline can never be met, even on a fast/unloaded machine.
        ScenarioConfig heavy = new ScenarioConfig(50_000, 20, 0.3, 0.05, 20_000, List.of());
        BatchCancelledException e = assertThrows(BatchCancelledException.class,
                () -> runner.run(new BatchRequest(heavy, 100, 1L, 4, 1)));
        assertTrue(e.isTimeout(), "expected a timeout, got: " + e.getMessage());
        log.info("timeout produced: {}", e.getMessage());
        assertNoLiveBatchWorkers(workersBefore);

        // The runner must still be usable afterwards, with no partial state carried over.
        AggregateResult after = runner.run(new BatchRequest(scenario(), 10, 5L, 2, 0));
        assertEquals(10, after.replications());
    }

    @Test
    void cancellationAbortsWholeBatchAndLeavesNoWorkers() throws Exception {
        long workersBefore = countBatchWorkerThreads();
        ScenarioConfig heavy = new ScenarioConfig(50_000, 20, 0.3, 0.05, 20_000, List.of());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread canceller = new Thread(() -> {
            try {
                runner.run(new BatchRequest(heavy, 100, 2L, 4, 0));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                // BatchRunner restores the caller's interrupt status on return;
                // consume it here so it cannot leak onto whatever this JUnit
                // worker thread runs next.
                Thread.interrupted();
            }
        }, "cancelling-caller");
        canceller.start();
        awaitBatchWorkersRunning();
        canceller.interrupt();
        canceller.join(30_000);

        assertFalse(canceller.isAlive(), "batch did not react to cancellation in time");
        assertTrue(failure.get() instanceof BatchCancelledException,
                "expected BatchCancelledException, got " + failure.get());
        assertFalse(((BatchCancelledException) failure.get()).isTimeout(),
                "explicit cancellation must not be reported as timeout");
        log.info("cancellation produced: {}", failure.get().getMessage());
        assertNoLiveBatchWorkers(workersBefore);
    }

    /**
     * Real-engine version of the strict guarantee, with zero grace period:
     * evaluated at the exact instant run() reports cancellation, no
     * batch-worker thread may still be alive.
     */
    @Test
    void realEngineParallelCancellationHasNoLiveWorkersAtReturnInstant() throws Exception {
        ScenarioConfig heavy = new ScenarioConfig(50_000, 20, 0.3, 0.05, 20_000, List.of());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread canceller = new Thread(() -> {
            try {
                runner.run(new BatchRequest(heavy, 100, 11L, 8, 0));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                Thread.interrupted();
            }
        }, "real-engine-cancelling-caller");
        canceller.start();
        awaitBatchWorkersRunning();
        canceller.interrupt();
        canceller.join(30_000);

        assertFalse(canceller.isAlive(), "batch did not react to cancellation in time");
        assertTrue(failure.get() instanceof BatchCancelledException,
                "expected BatchCancelledException, got " + failure.get());
        assertFalse(((BatchCancelledException) failure.get()).isTimeout(),
                "cancellation must not be reported as timeout");
        // Strict, no polling/grace: at the return instant itself.
        long liveAtReturn = countBatchWorkerThreads();
        org.junit.jupiter.api.Assertions.assertEquals(0L, liveAtReturn,
                "at the instant cancellation returned, batch-worker threads were still alive");
    }

    /**
     * Cancel several heavy real-engine batches back-to-back on one runner; at
     * each return instant no workers linger, and a final healthy batch succeeds.
     */
    @Test
    void repeatedRealEngineCancellationsStayCleanAndDoNotPoisonRunner() throws Exception {
        ScenarioConfig heavy = new ScenarioConfig(50_000, 20, 0.3, 0.05, 20_000, List.of());
        for (int round0 = 0; round0 < 3; round0++) {
            final int round = round0;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread canceller = new Thread(() -> {
                try {
                    runner.run(new BatchRequest(heavy, 100, 20L + round, 8, 0));
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    Thread.interrupted();
                }
            }, "repeated-cancelling-caller-" + round);
            canceller.start();
            awaitBatchWorkersRunning();
            canceller.interrupt();
            canceller.join(30_000);
            assertFalse(canceller.isAlive(), "round " + round + " did not return");
            assertTrue(failure.get() instanceof BatchCancelledException,
                    "round " + round + ": expected BatchCancelledException, got " + failure.get());
            assertFalse(((BatchCancelledException) failure.get()).isTimeout(),
                    "round " + round + ": cancellation misreported as timeout");
            org.junit.jupiter.api.Assertions.assertEquals(0L, countBatchWorkerThreads(),
                    "round " + round + ": workers alive at the cancellation return instant");
        }
        AggregateResult after = runner.run(new BatchRequest(scenario(), 12, 7L, 4, 0));
        assertEquals(12, after.replications());
    }

    /**
     * Serial cancellation must report cancellation and never timeout. Instead
     * of guessing a warmup delay, the driver waits until the caller thread is
     * observably inside BatchRunner.run, then interrupts it — deterministic
     * regardless of scheduling.
     */
    @Test
    void serialCancellationIsCancellationNotTimeout() throws Exception {
        ScenarioConfig heavy = new ScenarioConfig(50_000, 20, 0.3, 0.05, 20_000, List.of());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread canceller = new Thread(() -> {
            try {
                runner.run(new BatchRequest(heavy, 1_000, 30L, 1, 0));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                Thread.interrupted();
            }
        }, "serial-cancelling-caller");
        canceller.start();
        awaitThreadInMethod(canceller, "com.github.h3yow.epidemicsimulationjava.batch.BatchRunner.run");
        canceller.interrupt();
        canceller.join(30_000);
        assertFalse(canceller.isAlive());
        assertTrue(failure.get() instanceof BatchCancelledException,
                "expected BatchCancelledException, got " + failure.get());
        assertFalse(((BatchCancelledException) failure.get()).isTimeout(),
                "serial cancellation must not be reported as timeout");
    }

    /**
     * Polls the target thread's stack until {@code method} appears, proving the
     * thread has entered that method (so a subsequent interrupt lands in-flight).
     */
    private static void awaitThreadInMethod(Thread target, String method) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            for (StackTraceElement e : target.getStackTrace()) {
                if ((e.getClassName() + "." + e.getMethodName()).equals(method)) {
                    return;
                }
            }
            if (!target.isAlive()) {
                throw new AssertionError("target thread exited before entering " + method);
            }
            Thread.sleep(2);
        }
        throw new AssertionError("target thread never entered " + method);
    }

    @Test
    void invalidBatchParametersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(new BatchRequest(scenario(), 0, 1L, 1, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(new BatchRequest(scenario(), 5, 1L, 0, 0)));
    }
}
