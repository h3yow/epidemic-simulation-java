package com.github.h3yow.epidemicsimulationjava.model;

import java.util.List;

/**
 * Immutable description of one epidemic scenario.
 *
 * @param population       total population size N (constant, no births/deaths)
 * @param initialInfected  initially infectious individuals I(0); S(0) = N - I(0), R(0) = 0
 * @param beta             baseline transmission rate per hour
 * @param gamma            recovery rate per hour
 * @param horizonHours     number of hourly steps to simulate; hours are 0..horizonHours-1
 * @param interventions    interventions with declared activation windows
 */
public record ScenarioConfig(
        long population,
        long initialInfected,
        double beta,
        double gamma,
        int horizonHours,
        List<Intervention> interventions) {

    public ScenarioConfig {
        interventions = interventions == null ? List.of() : List.copyOf(interventions);
    }
}
