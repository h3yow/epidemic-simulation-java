package com.github.h3yow.epidemicsimulationjava.simulation;

import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;

/**
 * Request for one stochastic scenario replication.
 *
 * @param scenario scenario to simulate
 * @param seed     random seed; if null a seed is generated and reported in the response
 */
public record SingleSimulationRequest(ScenarioConfig scenario, Long seed) {
}
