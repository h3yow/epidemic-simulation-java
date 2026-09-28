package com.github.h3yow.epidemicsimulationjava.capacity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single authority for pre-execution capacity checks. Every compute-heavy entry
 * point (single simulation, batch replication, parameter calibration) must call
 * one of these methods <b>before scheduling any work</b>.
 *
 * <p>A failed check throws {@link CapacityExceededException}; nothing else
 * happens (no executors, no simulations), so rejected requests can never run
 * partially or continue in the background.
 *
 * <p>Check order is fixed (scalar dimensions from cheap to expensive, aggregate
 * work last), so the reported reason is deterministic for any given request.
 */
@Component
public class CapacityGuard {

    private static final Logger log = LoggerFactory.getLogger(CapacityGuard.class);

    private final CapacityLimits limits;

    public CapacityGuard(CapacityLimits limits) {
        this.limits = limits;
    }

    /** Capacity check for a single stochastic replication. */
    public void checkSingle(long population, int horizonHours) {
        checkPopulation(population);
        checkHorizon(horizonHours);
    }

    /** Capacity check for a batch of replications. */
    public void checkBatch(long population, int horizonHours, int replications, int parallelism) {
        checkPopulation(population);
        checkHorizon(horizonHours);
        checkReplications(replications);
        checkParallelism(parallelism);
        checkTotalWork((long) replications * horizonHours);
    }

    /** Capacity check for a parameter calibration request. */
    public void checkCalibration(long population, int horizonHours, int replications,
                                 int parallelism, int maxEvaluations, int observations) {
        checkPopulation(population);
        checkHorizon(horizonHours);
        checkReplications(replications);
        checkParallelism(parallelism);
        checkEvaluations(maxEvaluations);
        checkObservations(observations);
        // evaluations x replications x horizon: the full optimisation budget
        checkTotalWork((long) maxEvaluations * replications * horizonHours);
    }

    private void checkPopulation(long population) {
        if (population > limits.maxPopulation()) {
            reject(CapacityDimension.POPULATION, population, limits.maxPopulation());
        }
    }

    private void checkHorizon(int horizonHours) {
        if (horizonHours > limits.maxHorizonHours()) {
            reject(CapacityDimension.HORIZON_HOURS, horizonHours, limits.maxHorizonHours());
        }
    }

    private void checkReplications(int replications) {
        if (replications > limits.maxReplications()) {
            reject(CapacityDimension.REPLICATIONS, replications, limits.maxReplications());
        }
    }

    private void checkParallelism(int parallelism) {
        if (parallelism > limits.maxParallelism()) {
            reject(CapacityDimension.PARALLELISM, parallelism, limits.maxParallelism());
        }
    }

    private void checkEvaluations(int maxEvaluations) {
        if (maxEvaluations > limits.maxEvaluations()) {
            reject(CapacityDimension.MAX_EVALUATIONS, maxEvaluations, limits.maxEvaluations());
        }
    }

    private void checkObservations(int observations) {
        if (observations > limits.maxObservations()) {
            reject(CapacityDimension.OBSERVATIONS, observations, limits.maxObservations());
        }
    }

    private void checkTotalWork(long totalSimulatedHours) {
        if (totalSimulatedHours > limits.maxTotalSimulatedHours()) {
            reject(CapacityDimension.TOTAL_SIMULATED_HOURS,
                    totalSimulatedHours, limits.maxTotalSimulatedHours());
        }
    }

    private static void reject(CapacityDimension dimension, long requested, long limit) {
        log.warn("capacity rejected: dimension={} requested={} limit={}",
                dimension.code(), requested, limit);
        throw new CapacityExceededException(dimension, requested, limit);
    }

    /** @return the currently effective ceilings (for diagnostics/tests) */
    public CapacityLimits limits() {
        return limits;
    }
}
