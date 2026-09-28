package com.github.h3yow.epidemicsimulationjava.api;

import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;

/**
 * Request for a single stochastic scenario replication.
 *
 * @param scenario scenario to simulate
 * @param seed     seed for the replication; same (scenario, seed) always gives the same trajectory
 */
public record SimulationRequest(ScenarioConfig scenario, long seed) {
}
