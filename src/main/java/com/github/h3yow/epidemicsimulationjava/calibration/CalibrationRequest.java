package com.github.h3yow.epidemicsimulationjava.calibration;

import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;

/**
 * Request to calibrate the transmission rate beta against observed data.
 *
 * @param baseScenario    scenario template; its beta is used as the initial guess
 * @param observedInfected observed infectious counts, one per hour, aligned with hour 0 of the scenario
 * @param betaLower       lower bound for beta (exclusive of 0)
 * @param betaUpper       upper bound for beta
 * @param replications    stochastic replications per objective evaluation
 * @param masterSeed      master seed for reproducible objective evaluations
 * @param parallelism     worker threads for batch evaluations
 * @param maxEvaluations  optimisation budget: maximum number of objective evaluations
 */
public record CalibrationRequest(
        ScenarioConfig baseScenario,
        double[] observedInfected,
        double betaLower,
        double betaUpper,
        int replications,
        long masterSeed,
        int parallelism,
        int maxEvaluations) {
}
