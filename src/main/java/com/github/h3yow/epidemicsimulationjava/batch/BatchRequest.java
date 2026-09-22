package com.github.h3yow.epidemicsimulationjava.batch;

import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;

/**
 * Request for a batch of stochastic replications of one scenario.
 *
 * @param scenario      scenario to replicate
 * @param replications  number of stochastic repetitions (>= 1)
 * @param masterSeed    master seed; per-replication seeds are derived deterministically
 * @param parallelism   worker thread count (>= 1); 1 means strictly serial execution
 * @param timeoutMillis overall timeout for the whole batch (<= 0 means no timeout)
 */
public record BatchRequest(
        ScenarioConfig scenario,
        int replications,
        long masterSeed,
        int parallelism,
        long timeoutMillis) {
}
