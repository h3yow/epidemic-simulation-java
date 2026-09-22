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
    void timeoutAbortsWholeBatchAndLeavesNoWorkers() {
        long workersBefore = countBatchWorkerThreads();
        // Heavy request with a tiny timeout: cannot possibly finish.
        ScenarioConfig heavy = new ScenarioConfig(50_000, 20, 0.3, 0.05, 20_000, List.of());
        BatchCancelledException e = assertThrows(BatchCancelledException.class,
                () -> runner.run(new BatchRequest(heavy, 100, 1L, 4, 30)));
        assertTrue(e.isTimeout(), "expected a timeout, got: " + e.getMessage());
        log.info("timeout produced: {}", e.getMessage());
        assertEquals(workersBefore, countBatchWorkerThreads(),
                "worker threads must not survive a timed-out batch");

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
            }
        }, "cancelling-caller");
        canceller.start();
        Thread.sleep(50); // let the batch get going
        canceller.interrupt();
        canceller.join(30_000);

        assertFalse(canceller.isAlive(), "batch did not react to cancellation in time");
        assertTrue(failure.get() instanceof BatchCancelledException,
                "expected BatchCancelledException, got " + failure.get());
        assertFalse(((BatchCancelledException) failure.get()).isTimeout(),
                "explicit cancellation must not be reported as timeout");
        log.info("cancellation produced: {}", failure.get().getMessage());
        assertEquals(workersBefore, countBatchWorkerThreads(),
                "worker threads must not survive a cancelled batch");
    }

    @Test
    void invalidBatchParametersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(new BatchRequest(scenario(), 0, 1L, 1, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(new BatchRequest(scenario(), 5, 1L, 0, 0)));
    }
}
