package com.github.h3yow.epidemicsimulationjava.capacity;

/**
 * Immutable set of resource upper bounds enforced before any heavy computation starts.
 *
 * <p>All bounds are inclusive: a request equal to a bound is accepted.
 *
 * @param maxPopulation             max N per scenario
 * @param maxHorizonHours           max simulated hours per replication
 * @param maxReplications           max replications per batch / calibration evaluation
 * @param maxParallelism            max worker threads per request
 * @param maxCalibrationEvaluations max objective evaluations per calibration
 * @param maxTotalSimulatedHours    max estimated total simulated hours per request
 */
public record CapacityLimits(
        long maxPopulation,
        long maxHorizonHours,
        long maxReplications,
        long maxParallelism,
        long maxCalibrationEvaluations,
        long maxTotalSimulatedHours) {

    /** Reasonable local defaults; every value can be overridden via external config. */
    public static CapacityLimits defaults() {
        return new CapacityLimits(
                1_000_000L,
                50_000L,
                1_000L,
                16L,
                500L,
                20_000_000L);
    }
}
