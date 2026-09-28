package com.github.h3yow.epidemicsimulationjava.capacity;

/**
 * Resource dimensions enforced uniformly at every compute-heavy entry point
 * (single simulation, batch replication, parameter calibration).
 *
 * <p>Every value is serialised as a stable machine-readable code returned to
 * callers when a request is rejected, so clients can distinguish <em>which</em>
 * limit was exceeded.
 */
public enum CapacityDimension {
    /** Population size N exceeds the configured ceiling. */
    POPULATION("POPULATION_TOO_LARGE"),
    /** Simulated horizon (hour count) exceeds the configured ceiling. */
    HORIZON_HOURS("HORIZON_TOO_LONG"),
    /** Requested stochastic replications exceed the configured ceiling. */
    REPLICATIONS("TOO_MANY_REPLICATIONS"),
    /** Requested worker parallelism exceeds the configured ceiling. */
    PARALLELISM("PARALLELISM_TOO_HIGH"),
    /** Optimisation evaluation budget exceeds the configured ceiling. */
    MAX_EVALUATIONS("TOO_MANY_EVALUATIONS"),
    /** Observation vector length exceeds the configured ceiling. */
    OBSERVATIONS("TOO_MANY_OBSERVATIONS"),
    /**
     * Aggregate work volume (replications &times; evaluations &times; simulated
     * hours) exceeds the configured ceiling.
     */
    TOTAL_SIMULATED_HOURS("TOTAL_WORK_TOO_LARGE");

    private final String code;

    CapacityDimension(String code) {
        this.code = code;
    }

    /** @return stable machine-readable code used in error responses */
    public String code() {
        return code;
    }
}
