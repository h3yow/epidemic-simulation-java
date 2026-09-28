package com.github.h3yow.epidemicsimulationjava.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.h3yow.epidemicsimulationjava.batch.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRunner;
import com.github.h3yow.epidemicsimulationjava.calibration.CalibrationFailureReason;
import com.github.h3yow.epidemicsimulationjava.calibration.CalibrationRequest;
import com.github.h3yow.epidemicsimulationjava.calibration.CalibrationResult;
import com.github.h3yow.epidemicsimulationjava.calibration.Calibrator;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import com.github.h3yow.epidemicsimulationjava.simulation.SingleSimulationRequest;
import com.github.h3yow.epidemicsimulationjava.simulation.SimulationService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * All three compute-heavy entries reject oversized requests before any work
 * starts, with the right dimension code, and leave no worker threads behind.
 */
class CapacityRejectionIntegrationTest {

    private static long batchWorkersAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("batch-worker") && t.isAlive())
                .count();
    }

    private static ScenarioConfig scenario(long population, int horizon) {
        return new ScenarioConfig(population, 1, 0.3, 0.05, horizon, List.of());
    }

    @Test
    void singleSimulationRejectsBeforeComputingAndLeavesNoWorkers() {
        CapacityGuard guard = new CapacityGuard(CapacityLimits.defaults());
        SimulationService service = new SimulationService(guard);
        long before = batchWorkersAlive();

        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> service.run(new SingleSimulationRequest(scenario(10_000_001, 10), 1L)));
        assertEquals(CapacityDimension.POPULATION, e.dimension());

        CapacityExceededException horizon = assertThrows(CapacityExceededException.class,
                () -> service.run(new SingleSimulationRequest(scenario(100, 100_001), 1L)));
        assertEquals(CapacityDimension.HORIZON_HOURS, horizon.dimension());

        assertEquals(before, batchWorkersAlive(), "rejection must not spawn workers");
    }

    @Test
    void batchRejectsBeforeSchedulingTasksAndLeavesNoWorkers() {
        BatchRunner runner = new BatchRunner();
        long before = batchWorkersAlive();

        CapacityExceededException reps = assertThrows(CapacityExceededException.class,
                () -> runner.run(new BatchRequest(scenario(100, 10), 1_001, 1L, 1, 0)));
        assertEquals(CapacityDimension.REPLICATIONS, reps.dimension());

        CapacityExceededException work = assertThrows(CapacityExceededException.class,
                () -> runner.run(new BatchRequest(scenario(1000, 20_010), 1_000, 1L, 1, 0)));
        assertEquals(CapacityDimension.TOTAL_SIMULATED_HOURS, work.dimension());

        CapacityExceededException parallelism = assertThrows(CapacityExceededException.class,
                () -> runner.run(new BatchRequest(scenario(100, 10), 5, 1L, 17, 0)));
        assertEquals(CapacityDimension.PARALLELISM, parallelism.dimension());

        assertEquals(before, batchWorkersAlive(), "rejected batch must leave no workers");
    }

    @Test
    void calibrationRejectsBeforeRunningAndCarriesDimensionCode() {
        Calibrator calibrator = new Calibrator(new BatchRunner());
        double[] observed = new double[100];
        long before = batchWorkersAlive();

        CalibrationResult reps = calibrator.calibrate(new CalibrationRequest(
                scenario(100, 1000), observed, 0.1, 0.8, 1_001, 1L, 1, 10));
        assertEquals(CalibrationFailureReason.RESOURCE_LIMIT_EXCEEDED, reps.failureReason());
        assertEquals(CapacityDimension.REPLICATIONS.code(), reps.capacityReason());

        CalibrationResult work = calibrator.calibrate(new CalibrationRequest(
                scenario(1000, 20_001), observed, 0.1, 0.8, 100, 1L, 1, 10));
        assertEquals(CalibrationFailureReason.RESOURCE_LIMIT_EXCEEDED, work.failureReason());
        assertEquals(CapacityDimension.TOTAL_SIMULATED_HOURS.code(), work.capacityReason());

        CalibrationResult evals = calibrator.calibrate(new CalibrationRequest(
                scenario(100, 1000), observed, 0.1, 0.8, 10, 1L, 1, 501));
        assertEquals(CapacityDimension.MAX_EVALUATIONS.code(), evals.capacityReason());

        assertEquals(0, reps.evaluationsUsed(), "rejected before any objective evaluation");
        assertEquals(0, work.evaluationsUsed());
        assertEquals(0, evals.evaluationsUsed());
        assertEquals(before, batchWorkersAlive(), "rejected calibration must leave no workers");
    }
}
