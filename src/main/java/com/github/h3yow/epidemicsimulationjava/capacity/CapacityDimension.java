package com.github.h3yow.epidemicsimulationjava.capacity;

/**
 * Resource dimensions enforced uniformly by the capacity guard.
 *
 * <p>Each dimension maps to one externally configurable upper bound and
 * produces one machine-distinguishable rejection code.
 */
public enum CapacityDimension {

    /** Single-scenario population size N. */
    POPULATION("population"),
    /** Simulated hours per replication (scenario horizon). */
    HORIZON_HOURS("horizonHours"),
    /** Replications requested by a batch (or per calibration evaluation). */
    REPLICATIONS("replications"),
    /** Worker threads requested by a batch or calibration evaluation. */
    PARALLELISM("parallelism"),
    /** Objective-evaluation budget of a calibration request. */
    CALIBRATION_EVALUATIONS("calibrationEvaluations"),
    /** Estimated total simulated hours: replications x horizon (x evaluations). */
    TOTAL_SIMULATED_HOURS("totalSimulatedHours");

    private final String property;

    CapacityDimension(String property) {
        this.property = property;
    }

    /** @return external config key suffix for this dimension's bound */
    public String property() {
        return property;
    }
}
