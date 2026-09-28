package com.github.h3yow.epidemicsimulationjava.api;

/**
 * Outcome of a single scenario replication.
 *
 * @param seed           seed actually used
 * @param peakInfected   maximum infectious count over the horizon
 * @param totalRecovered recovered count at the end of the horizon
 * @param hourlyInfected infectious count at the end of each simulated hour
 */
public record SimulationResponse(
        long seed,
        long peakInfected,
        long totalRecovered,
        long[] hourlyInfected) {
}
