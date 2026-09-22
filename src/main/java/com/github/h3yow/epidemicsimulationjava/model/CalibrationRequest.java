package com.github.h3yow.epidemicsimulationjava.model;

import java.time.Duration;
import java.util.List;

/** 基于观测数据对 beta（每步传播率）做网格校准的请求。 */
public record CalibrationRequest(
        EpidemicScenario scenario,
        List<Observation> observations,
        double betaLow,
        double betaHigh,
        int gridPoints,
        int replicationsPerPoint,
        double rmseTolerance,
        Duration timeout
) {
}
