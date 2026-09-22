package com.github.h3yow.epidemicsimulationjava.simulation;

/**
 * Outcome of a single stochastic replication.
 *
 * @param replicationIndex index of the replication within its batch (0-based)
 * @param seed             the exact seed used for this replication
 * @param hourlyInfected   infectious count at the end of each simulated hour, length = horizonHours
 * @param peakInfected     maximum infectious count over the horizon
 * @param totalRecovered   R at the end of the horizon (cumulative ever-infected minus initial)
 */
public record ReplicationResult(
        int replicationIndex,
        long seed,
        long[] hourlyInfected,
        long peakInfected,
        long totalRecovered) {
}
