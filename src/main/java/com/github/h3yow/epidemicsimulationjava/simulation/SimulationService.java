package com.github.h3yow.epidemicsimulationjava.simulation;

import com.github.h3yow.epidemicsimulationjava.capacity.CapacityGuard;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioValidator;
import java.util.SplittableRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Runs a single stochastic scenario replication, with pre-execution capacity enforcement. */
@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final CapacityGuard capacityGuard;
    private final SplittableRandom seedSource = new SplittableRandom();

    public SimulationService(CapacityGuard capacityGuard) {
        this.capacityGuard = capacityGuard;
    }

    /**
     * Validates scenario and capacity, then runs exactly one replication.
     * Capacity is checked before any computation starts; a rejected request
     * performs no simulation and leaves nothing running in the background.
     *
     * @param request single simulation request
     * @return replication outcome (the effective seed is always recorded)
     */
    public ReplicationResult run(SingleSimulationRequest request) {
        if (request == null || request.scenario() == null) {
            throw new IllegalArgumentException("request and scenario must not be null");
        }
        ScenarioConfig scenario = request.scenario();
        ScenarioValidator.validate(scenario);
        capacityGuard.checkSingle(scenario.population(), scenario.horizonHours());

        long seed = request.seed() != null ? request.seed() : seedSource.nextLong();
        log.info("single simulation start: seed={} population={} horizonHours={} beta={} gamma={}",
                seed, scenario.population(), scenario.horizonHours(), scenario.beta(),
                scenario.gamma());
        ReplicationResult result = SimulationEngine.run(scenario, seed, 0);
        log.info("single simulation done: seed={} peakInfected={} totalRecovered={}",
                seed, result.peakInfected(), result.totalRecovered());
        return result;
    }
}
