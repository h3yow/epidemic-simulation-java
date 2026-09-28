package com.github.h3yow.epidemicsimulationjava.capacity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Every resource dimension is enforced and reports its own stable code. */
class CapacityGuardTest {

    private final CapacityGuard guard = new CapacityGuard(CapacityLimits.defaults());

    @Test
    void requestsWithinCeilingsPass() {
        assertDoesNotThrow(() -> guard.checkSingle(1_000, 1_000));
        assertDoesNotThrow(() -> guard.checkBatch(1_000, 1_000, 100, 4));
        assertDoesNotThrow(() -> guard.checkCalibration(1_000, 1_000, 50, 4, 100, 200));
    }

    @Test
    void populationIsEnforcedForSingleBatchAndCalibration() {
        assertDimension(CapacityDimension.POPULATION,
                () -> guard.checkSingle(10_000_001, 10));
        assertDimension(CapacityDimension.POPULATION,
                () -> guard.checkBatch(10_000_001, 10, 1, 1));
        assertDimension(CapacityDimension.POPULATION,
                () -> guard.checkCalibration(10_000_001, 10, 1, 1, 1, 5));
    }

    @Test
    void horizonIsEnforcedForEveryEntryPoint() {
        assertDimension(CapacityDimension.HORIZON_HOURS,
                () -> guard.checkSingle(10, 100_001));
        assertDimension(CapacityDimension.HORIZON_HOURS,
                () -> guard.checkBatch(10, 100_001, 1, 1));
        assertDimension(CapacityDimension.HORIZON_HOURS,
                () -> guard.checkCalibration(10, 100_001, 1, 1, 1, 5));
    }

    @Test
    void replicationsParallelismEvaluationsAndObservationsAreEnforced() {
        assertDimension(CapacityDimension.REPLICATIONS,
                () -> guard.checkBatch(10, 10, 1_001, 1));
        assertDimension(CapacityDimension.PARALLELISM,
                () -> guard.checkBatch(10, 10, 1, 17));
        assertDimension(CapacityDimension.REPLICATIONS,
                () -> guard.checkCalibration(10, 10, 1_001, 1, 1, 5));
        assertDimension(CapacityDimension.PARALLELISM,
                () -> guard.checkCalibration(10, 10, 1, 17, 1, 5));
        assertDimension(CapacityDimension.MAX_EVALUATIONS,
                () -> guard.checkCalibration(10, 10, 1, 1, 501, 5));
        assertDimension(CapacityDimension.OBSERVATIONS,
                () -> guard.checkCalibration(10, 10, 1, 1, 1, 100_001));
    }

    @Test
    void aggregateWorkVolumeIsEnforced() {
        // 1000 reps * 20010 hours = 20,010,000 > 20,000,000 (scalars all within ceilings)
        assertDimension(CapacityDimension.TOTAL_SIMULATED_HOURS,
                () -> guard.checkBatch(1000, 20_010, 1_000, 1));
        // 10 evaluations * 100 reps * 20_001 hours = 20,001,000 > budget
        assertDimension(CapacityDimension.TOTAL_SIMULATED_HOURS,
                () -> guard.checkCalibration(1000, 20_001, 100, 1, 10, 100));
    }

    @Test
    void exceptionCarriesRequestedLimitAndStableCode() {
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkBatch(10, 10, 4_242, 1));
        assertEquals(CapacityDimension.REPLICATIONS, e.dimension());
        assertEquals("TOO_MANY_REPLICATIONS", e.dimension().code());
        assertEquals(4_242, e.requested());
        assertEquals(1_000, e.limit());
    }

    @Test
    void customLimitsAreHonoured() {
        CapacityLimits tight = new CapacityLimits(100, 50, 5, 2, 3, 10, 1_000);
        CapacityGuard tightGuard = new CapacityGuard(tight);
        assertDimension(CapacityDimension.POPULATION,
                () -> tightGuard.checkSingle(101, 10));
        assertDoesNotThrow(() -> tightGuard.checkSingle(100, 50));
        assertDimension(CapacityDimension.REPLICATIONS,
                () -> tightGuard.checkBatch(10, 10, 6, 1));
        assertDimension(CapacityDimension.MAX_EVALUATIONS,
                () -> tightGuard.checkCalibration(10, 10, 5, 2, 4, 5));
        assertDoesNotThrow(() -> tightGuard.checkCalibration(10, 10, 5, 2, 2, 5));
    }

    private static void assertDimension(CapacityDimension expected, Runnable check) {
        CapacityExceededException e = assertThrows(CapacityExceededException.class, check::run);
        assertEquals(expected, e.dimension());
    }
}
