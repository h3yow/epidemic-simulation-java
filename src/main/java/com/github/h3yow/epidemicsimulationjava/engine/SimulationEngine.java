package com.github.h3yow.epidemicsimulationjava.engine;

import com.github.h3yow.epidemicsimulationjava.model.EpidemicScenario;
import com.github.h3yow.epidemicsimulationjava.model.ReplicationResult;

/** 单条情景轨迹的逐时推进引擎。 */
public interface SimulationEngine {

    ReplicationResult runReplication(EpidemicScenario scenario, long seed, int replicateIndex);
}
