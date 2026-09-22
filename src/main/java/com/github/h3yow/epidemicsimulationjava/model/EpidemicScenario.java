package com.github.h3yow.epidemicsimulationjava.model;

import java.util.List;

/**
 * 传播情景：离散时间 SEIR 模型的完整参数化描述。
 */
public record EpidemicScenario(
        String name,
        int population,
        int initialExposed,
        int initialInfectious,
        double betaPerStep,
        double sigmaPerStep,
        double gammaPerStep,
        int steps,
        List<Intervention> interventions
) {
}
