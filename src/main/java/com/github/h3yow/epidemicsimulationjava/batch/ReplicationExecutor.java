package com.github.h3yow.epidemicsimulationjava.batch;

import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import com.github.h3yow.epidemicsimulationjava.simulation.ReplicationResult;

/**
 * Executes a single replication within a batch.
 *
 * <p>This is a package-private seam: production always uses
 * {@link com.github.h3yow.epidemicsimulationjava.simulation.SimulationEngine#run},
 * while tests can substitute a task whose reaction to interruption is controllable,
 * so cancellation/cleanup behaviour can be reproduced deterministically.
 */
@FunctionalInterface
interface ReplicationExecutor {
    ReplicationResult run(ScenarioConfig scenario, long seed, int index);
}
