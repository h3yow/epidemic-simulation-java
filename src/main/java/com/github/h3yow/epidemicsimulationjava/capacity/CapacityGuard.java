package com.github.h3yow.epidemicsimulationjava.capacity;

import com.github.h3yow.epidemicsimulationjava.batch.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.calibration.CalibrationRequest;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Single place enforcing the externally configured capacity limits on every
 * compute-heavy entry point (single simulation, batch replication, calibration).
 *
 * <p>All checks run strictly before any task is scheduled, seed is derived or
 * thread pool is created. Over-limit requests are rejected with a
 * {@link CapacityExceededException} carrying the violated
 * {@link CapacityDimension}; the first violated dimension (fixed check order)
 * is reported, so callers can branch on it deterministically.
 */
@Component
public class CapacityGuard {

    private static final Logger log = LoggerFactory.getLogger(CapacityGuard.class);

    private final CapacityLimits limits;

    /** Used by Spring with the bound configuration properties. */
    @Autowired
    public CapacityGuard(CapacityProperties properties) {
        this(properties.toLimits());
    }

    /** Convenience constructor for tests and non-Spring usage with default limits. */
    public CapacityGuard() {
        this(CapacityLimits.defaults());
    }

    /** Convenience constructor with explicit limits. */
    public CapacityGuard(CapacityLimits limits) {
        this.limits = limits;
    }

    /** @return the currently effective limits */
    public CapacityLimits limits() {
        return limits;
    }

    /** Validates a single-scenario simulation request. */
    public void checkSimulation(ScenarioConfig scenario) {
        if (scenario == null) {
            throw new IllegalArgumentException("scenario must not be null");
        }
        checkPopulation(scenario.population());
        checkHorizon(scenario.horizonHours());
    }

    /** Validates a batch replication request. */
    public void checkBatch(BatchRequest request) {
        if (request == null || request.scenario() == null) {
            throw new IllegalArgumentException("request and scenario must not be null");
        }
        checkPopulation(request.scenario().population());
        checkHorizon(request.scenario().horizonHours());
        checkReplications(request.replications());
        checkParallelism(request.parallelism());
        long total = (long) request.replications() * request.scenario().horizonHours();
        checkTotalHours(total, "replications(" + request.replications()
                + ") x horizonHours(" + request.scenario().horizonHours() + ")");
    }

    /** Validates a calibration request. */
    public void checkCalibration(CalibrationRequest request) {
        if (request == null || request.baseScenario() == null) {
            throw new IllegalArgumentException("request and baseScenario must not be null");
        }
        checkPopulation(request.baseScenario().population());
        checkHorizon(request.baseScenario().horizonHours());
        checkReplications(request.replications());
        checkParallelism(request.parallelism());
        checkEvaluations(request.maxEvaluations());
        long total = (long) request.maxEvaluations() * request.replications()
                * request.baseScenario().horizonHours();
        checkTotalHours(total, "evaluations(" + request.maxEvaluations()
                + ") x replications(" + request.replications()
                + ") x horizonHours(" + request.baseScenario().horizonHours() + ")");
    }

    private void checkPopulation(long population) {
        if (population > limits.maxPopulation()) {
            reject(CapacityDimension.POPULATION, population, limits.maxPopulation(),
                    "population " + population + " exceeds limit " + limits.maxPopulation());
        }
    }

    private void checkHorizon(int horizonHours) {
        if (horizonHours > limits.maxHorizonHours()) {
            reject(CapacityDimension.HORIZON_HOURS, horizonHours, limits.maxHorizonHours(),
                    "horizonHours " + horizonHours + " exceeds limit " + limits.maxHorizonHours());
        }
    }

    private void checkReplications(int replications) {
        if (replications > limits.maxReplications()) {
            reject(CapacityDimension.REPLICATIONS, replications, limits.maxReplications(),
                    "replications " + replications + " exceeds limit " + limits.maxReplications());
        }
    }

    private void checkParallelism(int parallelism) {
        if (parallelism > limits.maxParallelism()) {
            reject(CapacityDimension.PARALLELISM, parallelism, limits.maxParallelism(),
                    "parallelism " + parallelism + " exceeds limit " + limits.maxParallelism());
        }
    }

    private void checkEvaluations(int maxEvaluations) {
        if (maxEvaluations > limits.maxCalibrationEvaluations()) {
            reject(CapacityDimension.CALIBRATION_EVALUATIONS, maxEvaluations,
                    limits.maxCalibrationEvaluations(),
                    "maxEvaluations " + maxEvaluations + " exceeds limit "
                            + limits.maxCalibrationEvaluations());
        }
    }

    private void checkTotalHours(long total, String description) {
        if (total > limits.maxTotalSimulatedHours()) {
            reject(CapacityDimension.TOTAL_SIMULATED_HOURS, total,
                    limits.maxTotalSimulatedHours(),
                    "estimated work " + total + " simulated hours (" + description
                            + ") exceeds limit " + limits.maxTotalSimulatedHours());
        }
    }

    private static void reject(CapacityDimension dimension, long requested, long limit, String message) {
        log.warn("capacity exceeded: dimension={} requested={} limit={}", dimension, requested, limit);
        throw new CapacityExceededException(dimension, requested, limit, message);
    }
}
