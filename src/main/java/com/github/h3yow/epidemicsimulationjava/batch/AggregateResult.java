package com.github.h3yow.epidemicsimulationjava.batch;

import com.github.h3yow.epidemicsimulationjava.simulation.ReplicationResult;
import java.util.List;

/**
 * Aggregated batch outcome. Aggregation is order-stable (replication index order),
 * hence independent of parallelism and task partitioning.
 *
 * @param replications        number of replications included
 * @param meanPeakInfected    mean of peak infectious counts
 * @param peakInfectedP2_5    2.5% percentile of peak infectious counts
 * @param peakInfectedP97_5   97.5% percentile of peak infectious counts
 * @param meanTotalRecovered  mean of final recovered counts
 * @param results             per-replication results in index order
 */
public record AggregateResult(
        int replications,
        double meanPeakInfected,
        double peakInfectedP2_5,
        double peakInfectedP97_5,
        double meanTotalRecovered,
        List<ReplicationResult> results) {
}
