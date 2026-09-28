package com.github.h3yow.epidemicsimulationjava.capacity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.h3yow.epidemicsimulationjava.batch.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.calibration.CalibrationRequest;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the unified capacity guard: every resource dimension is
 * enforced on every compute-heavy entry point with a distinguishable reason.
 */
class CapacityGuardTest {

    /** Tight, round limits chosen so each dimension can be violated in isolation. */
    private final CapacityGuard guard = new CapacityGuard(new CapacityLimits(
            1_000L, 10_000L, 10L, 4L, 20L, 50_000L));

    private static ScenarioConfig scenario(long population, int horizon) {
        return new ScenarioConfig(population, 10, 0.3, 0.08, horizon, List.of());
    }

    private static BatchRequest batch(long population, int horizon, int reps, int parallelism) {
        return new BatchRequest(scenario(population, horizon), reps, 1L, parallelism, 0);
    }

    // ------------------------------------------------------------------
    // Single-simulation entry: population and horizon dimensions
    // ------------------------------------------------------------------

    @Test
    void simulationWithinLimitsPasses() {
        assertDoesNotThrow(() -> guard.checkSimulation(scenario(1_000, 100)));
    }

    @Test
    void simulationRejectsExcessivePopulation() {
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkSimulation(scenario(1_001, 10)));
        assertEquals(CapacityDimension.POPULATION, e.dimension());
        assertEquals(1_001, e.requested());
        assertEquals(1_000, e.limit());
    }

    @Test
    void simulationRejectsExcessiveHorizon() {
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkSimulation(scenario(100, 10_001)));
        assertEquals(CapacityDimension.HORIZON_HOURS, e.dimension());
        assertEquals(10_001, e.requested());
    }

    @Test
    void boundIsInclusive() {
        assertDoesNotThrow(() -> guard.checkSimulation(scenario(1_000, 10_000)));
        assertDoesNotThrow(() -> guard.checkBatch(batch(100, 10, 10, 4)));
    }

    // ------------------------------------------------------------------
    // Batch entry: every dimension
    // ------------------------------------------------------------------

    @Test
    void batchWithinLimitsPasses() {
        assertDoesNotThrow(() -> guard.checkBatch(batch(100, 10, 10, 4)));
    }

    @Test
    void batchRejectsPopulationDimension() {
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkBatch(batch(1_001, 10, 5, 2)));
        assertEquals(CapacityDimension.POPULATION, e.dimension());
    }

    @Test
    void batchRejectsHorizonDimension() {
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkBatch(batch(100, 10_001, 5, 2)));
        assertEquals(CapacityDimension.HORIZON_HOURS, e.dimension());
    }

    @Test
    void batchRejectsReplicationsDimension() {
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkBatch(batch(100, 10, 11, 2)));
        assertEquals(CapacityDimension.REPLICATIONS, e.dimension());
        assertEquals(11, e.requested());
        assertEquals(10, e.limit());
    }

    @Test
    void batchRejectsParallelismDimension() {
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkBatch(batch(100, 10, 5, 5)));
        assertEquals(CapacityDimension.PARALLELISM, e.dimension());
        assertEquals(5, e.requested());
        assertEquals(4, e.limit());
    }

    @Test
    void batchRejectsTotalSimulatedHoursDimension() {
        // 10 replications x 6,000 hours = 60,000 > 50,000; all other dims within limits.
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkBatch(batch(100, 6_000, 10, 4)));
        assertEquals(CapacityDimension.TOTAL_SIMULATED_HOURS, e.dimension());
        assertEquals(60_000L, e.requested());
    }

    // ------------------------------------------------------------------
    // Calibration entry: includes evaluations dimension
    // ------------------------------------------------------------------

    private static CalibrationRequest calibration(long population, int horizon, int reps,
                                                  int parallelism, int evaluations) {
        double[] observed = new double[Math.min(horizon, 10)];
        return new CalibrationRequest(scenario(population, horizon), observed,
                0.1, 0.8, reps, 1L, parallelism, evaluations);
    }

    @Test
    void calibrationWithinLimitsPasses() {
        assertDoesNotThrow(() -> guard.checkCalibration(calibration(100, 10, 5, 2, 20)));
    }

    @Test
    void calibrationRejectsPopulationDimension() {
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkCalibration(calibration(1_001, 10, 5, 2, 10)));
        assertEquals(CapacityDimension.POPULATION, e.dimension());
    }

    @Test
    void calibrationRejectsHorizonDimension() {
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkCalibration(calibration(100, 10_001, 5, 2, 10)));
        assertEquals(CapacityDimension.HORIZON_HOURS, e.dimension());
    }

    @Test
    void calibrationRejectsReplicationsDimension() {
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkCalibration(calibration(100, 10, 11, 2, 10)));
        assertEquals(CapacityDimension.REPLICATIONS, e.dimension());
    }

    @Test
    void calibrationRejectsParallelismDimension() {
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkCalibration(calibration(100, 10, 5, 5, 10)));
        assertEquals(CapacityDimension.PARALLELISM, e.dimension());
    }

    @Test
    void calibrationRejectsEvaluationsDimension() {
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkCalibration(calibration(100, 10, 5, 2, 21)));
        assertEquals(CapacityDimension.CALIBRATION_EVALUATIONS, e.dimension());
        assertEquals(21, e.requested());
        assertEquals(20, e.limit());
    }

    @Test
    void calibrationRejectsTotalSimulatedHoursDimension() {
        // 20 evaluations x 10 replications x 300 hours = 60,000 > 50,000; all other dims within limits.
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkCalibration(calibration(100, 300, 10, 4, 20)));
        assertEquals(CapacityDimension.TOTAL_SIMULATED_HOURS, e.dimension());
        assertEquals(60_000L, e.requested());
    }

    // ------------------------------------------------------------------
    // Configuration plumbing
    // ------------------------------------------------------------------

    @Test
    void propertiesBindAndOverrideDefaults() {
        CapacityProperties props = new CapacityProperties(
                2L, 3L, 4L, 5L, 6L, 7L);
        CapacityLimits limits = props.toLimits();
        assertEquals(2L, limits.maxPopulation());
        assertEquals(3L, limits.maxHorizonHours());
        assertEquals(4L, limits.maxReplications());
        assertEquals(5L, limits.maxParallelism());
        assertEquals(6L, limits.maxCalibrationEvaluations());
        assertEquals(7L, limits.maxTotalSimulatedHours());
    }

    @Test
    void nullPropertiesFallBackToDefaults() {
        CapacityLimits limits = new CapacityProperties(null, null, null, null, null, null)
                .toLimits();
        assertEquals(CapacityLimits.defaults(), limits);
    }

    @Test
    void nonPositiveConfiguredBoundIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CapacityProperties(0L, 3L, 4L, 5L, 6L, 7L));
    }
}
