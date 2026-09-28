package com.github.h3yow.epidemicsimulationjava.capacity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Externally configurable resource ceilings shared by every compute-heavy entry
 * point. Override any individual ceiling through standard Spring Boot property
 * sources, e.g. {@code epidemic.capacity.max-replications=2000} in
 * {@code application.properties}, {@code -Depidemic.capacity.max-replications=2000}
 * as a JVM system property, or {@code EPIDEMIC_CAPACITY_MAX_REPLICATIONS=2000}
 * in the environment.
 *
 * <p>All ceilings are inclusive: a request equal to a ceiling is allowed.
 *
 * @param maxPopulation          max population size N per scenario
 * @param maxHorizonHours        max simulated hours per replication
 * @param maxReplications        max stochastic replications per request
 * @param maxParallelism         max worker threads per request
 * @param maxEvaluations         max objective evaluations per calibration
 * @param maxObservations        max length of the observation vector
 * @param maxTotalSimulatedHours max aggregate simulated hours
 *                               (replications x evaluations x horizon)
 */
@ConfigurationProperties(prefix = "epidemic.capacity")
public record CapacityLimits(
        @DefaultValue("10000000") long maxPopulation,
        @DefaultValue("100000") int maxHorizonHours,
        @DefaultValue("1000") int maxReplications,
        @DefaultValue("16") int maxParallelism,
        @DefaultValue("500") int maxEvaluations,
        @DefaultValue("100000") int maxObservations,
        @DefaultValue("20000000") long maxTotalSimulatedHours) {

    /** Defaults used by plain unit tests outside the Spring context. */
    public static CapacityLimits defaults() {
        return new CapacityLimits(10_000_000L, 100_000, 1_000, 16, 500, 100_000, 20_000_000L);
    }
}
