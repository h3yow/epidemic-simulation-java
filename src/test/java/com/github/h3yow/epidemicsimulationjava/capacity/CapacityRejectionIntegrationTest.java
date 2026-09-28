package com.github.h3yow.epidemicsimulationjava.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.epidemicsimulationjava.batch.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRunner;
import com.github.h3yow.epidemicsimulationjava.calibration.CalibrationFailureReason;
import com.github.h3yow.epidemicsimulationjava.calibration.CalibrationRequest;
import com.github.h3yow.epidemicsimulationjava.calibration.CalibrationResult;
import com.github.h3yow.epidemicsimulationjava.calibration.Calibrator;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Over-limit requests must be rejected before any replication runs and must
 * leave no background worker behind. Covered at the service layer for all
 * three compute-heavy entries.
 */
class CapacityRejectionIntegrationTest {

    private static long liveBatchWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("batch-worker") && t.isAlive())
                .count();
    }

    private final CapacityGuard guard = new CapacityGuard(new CapacityLimits(
            10_000_000L, 100_000L, 5L, 2L, 20L, 50_000L));
    private final BatchRunner runner = new BatchRunner(guard);
    private final Calibrator calibrator = new Calibrator(runner, guard);

    private static ScenarioConfig scenario(int horizon) {
        return new ScenarioConfig(5_000, 15, 0.35, 0.08, horizon, List.of());
    }

    // ---- Single-simulation entry (through the guard used by the controller) ----

    @Test
    void oversizedSimulationIsRejectedBeforeAnyWorkAndLeavesNoWorkers() {
        long before = liveBatchWorkers();
        ScenarioConfig huge = scenario(200_000); // > 100,000 horizon bound
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkSimulation(huge));
        assertEquals(CapacityDimension.HORIZON_HOURS, e.dimension());
        // No batch machinery is ever involved for a rejected single run.
        assertEquals(before, liveBatchWorkers(), "rejection must not start workers");
    }

    // ---- Batch entry: rejection happens before seed derivation / pool creation ----

    @Test
    void oversizedBatchIsRejectedBeforeExecutionAndLeavesNoWorkers() {
        long before = liveBatchWorkers();
        BatchRequest tooManyReps = new BatchRequest(scenario(120), 6, 1L, 2, 0);
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> runner.run(tooManyReps));
        assertEquals(CapacityDimension.REPLICATIONS, e.dimension());
        assertEquals(6, e.requested());
        assertEquals(5, e.limit());
        assertEquals(before, liveBatchWorkers(), "rejected batch must not spawn workers");
    }

    @Test
    void oversizedBatchByParallelismLeavesNoWorkers() {
        long before = liveBatchWorkers();
        BatchRequest tooParallel = new BatchRequest(scenario(120), 4, 1L, 8, 0);
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> runner.run(tooParallel));
        assertEquals(CapacityDimension.PARALLELISM, e.dimension());
        assertEquals(before, liveBatchWorkers());
    }

    @Test
    void oversizedBatchByTotalHoursLeavesNoWorkers() {
        long before = liveBatchWorkers();
        // Dedicated tight total-hours budget: every single dimension is in bounds
        // (4 replications, 500-hour horizon), only their product 4 x 500 = 2,000
        // exceeds the 1,000 simulated-hours budget.
        CapacityGuard tight = new CapacityGuard(new CapacityLimits(
                10_000_000L, 10_000L, 1_000L, 8L, 500L, 1_000L));
        BatchRequest productHeavy = new BatchRequest(scenario(500), 4, 1L, 2, 0);
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> tight.checkBatch(productHeavy));
        assertEquals(CapacityDimension.TOTAL_SIMULATED_HOURS, e.dimension());
        assertEquals(2_000L, e.requested());
        assertEquals(before, liveBatchWorkers(), "rejection happens before scheduling");
    }

    @Test
    void rejectedBatchDoesNotStartAnyReplication() {
        // A scenario that would visibly run if the guard failed open: with
        // replications over the cap it must throw without touching the engine.
        BatchRequest bad = new BatchRequest(scenario(240), 50, 99L, 2, 0);
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> runner.run(bad));
        assertEquals(CapacityDimension.REPLICATIONS, e.dimension());
        // The runner remains usable afterwards and small batches still execute.
        assertEquals(2, runner.run(new BatchRequest(scenario(24), 2, 7L, 1, 0)).replications());
    }

    // ---- Calibration entry: rejected as a failed result, before any evaluation ----

    @Test
    void oversizedCalibrationIsRejectedBeforeAnyEvaluation() {
        long before = liveBatchWorkers();
        double[] observed = new double[120];
        for (int t = 0; t < observed.length; t++) {
            observed[t] = 100.0;
        }
        CalibrationRequest tooManyReps = new CalibrationRequest(
                scenario(120), observed, 0.1, 0.8, 50, 1L, 2, 20);
        CalibrationResult result = calibrator.calibrate(tooManyReps);
        assertFalse(result.success());
        assertEquals(CalibrationFailureReason.RESOURCE_LIMIT_EXCEEDED, result.failureReason());
        assertEquals(CapacityDimension.REPLICATIONS.name(), result.detailReason());
        assertEquals(0, result.evaluationsUsed(), "no objective evaluation may run after rejection");
        assertEquals(before, liveBatchWorkers(), "rejected calibration must not spawn workers");
    }

    @Test
    void oversizedCalibrationByEvaluationsIsRejectedWithDimension() {
        double[] observed = new double[120];
        CalibrationRequest tooManyEvals = new CalibrationRequest(
                scenario(120), observed, 0.1, 0.8, 4, 1L, 2, 21);
        CalibrationResult result = calibrator.calibrate(tooManyEvals);
        assertFalse(result.success());
        assertEquals(CalibrationFailureReason.RESOURCE_LIMIT_EXCEEDED, result.failureReason());
        assertEquals(CapacityDimension.CALIBRATION_EVALUATIONS.name(), result.detailReason());
        assertEquals(0, result.evaluationsUsed());
    }

    @Test
    void withinLimitBatchStillRunsAndCleansWorkersUp() throws Exception {
        long before = liveBatchWorkers();
        assertEquals(4, runner.run(new BatchRequest(scenario(120), 4, 3L, 2, 0)).replications());
        // Give the JVM no excuse: workers created for the batch must all be dead.
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline && liveBatchWorkers() > before) {
            Thread.sleep(10);
        }
        assertTrue(liveBatchWorkers() <= before, "no worker should outlive a completed batch");
    }
}
