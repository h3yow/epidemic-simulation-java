package com.github.h3yow.epidemicsimulationjava.model;

import com.github.h3yow.epidemicsimulationjava.model.InvalidScenarioException.Reason;

/**
 * Validates scenario configurations; rejects illegal or contradictory input eagerly.
 *
 * <p>Window semantics: an intervention is active during the half-open interval
 * [startHour, endHour). Two interventions of the same type whose windows overlap
 * (or are exact duplicates, which is a special case of overlap) are contradictory
 * and rejected. Different types may overlap freely; their effects combine
 * multiplicatively (transmission) or additively (treatment) at runtime.
 */
public final class ScenarioValidator {

    private ScenarioValidator() {
    }

    /**
     * Validates the scenario.
     *
     * @throws InvalidScenarioException if any rule is violated
     */
    public static void validate(ScenarioConfig config) {
        if (config == null) {
            throw new InvalidScenarioException(Reason.INVALID_POPULATION, "scenario must not be null");
        }
        if (config.population() <= 0) {
            throw new InvalidScenarioException(Reason.INVALID_POPULATION,
                    "population must be positive, got " + config.population());
        }
        if (config.initialInfected() <= 0 || config.initialInfected() >= config.population()) {
            throw new InvalidScenarioException(Reason.INVALID_INITIAL_INFECTED,
                    "initialInfected must be in (0, population), got " + config.initialInfected());
        }
        if (!(config.beta() > 0.0) || Double.isNaN(config.beta())) {
            throw new InvalidScenarioException(Reason.INVALID_RATE,
                    "beta must be a positive finite number, got " + config.beta());
        }
        if (!(config.gamma() > 0.0) || config.gamma() >= 1.0 || Double.isNaN(config.gamma())) {
            throw new InvalidScenarioException(Reason.INVALID_RATE,
                    "gamma must be in (0, 1), got " + config.gamma());
        }
        if (config.horizonHours() <= 0) {
            throw new InvalidScenarioException(Reason.INVALID_HORIZON,
                    "horizonHours must be positive, got " + config.horizonHours());
        }
        for (Intervention iv : config.interventions()) {
            validateIntervention(iv, config.horizonHours());
        }
        rejectSameTypeOverlaps(config);
    }

    private static void validateIntervention(Intervention iv, int horizonHours) {
        if (iv == null) {
            throw new InvalidScenarioException(Reason.INVALID_WINDOW, "intervention must not be null");
        }
        if (iv.startHour() < 0 || iv.endHour() <= iv.startHour()) {
            throw new InvalidScenarioException(Reason.INVALID_WINDOW,
                    "window must satisfy 0 <= startHour < endHour, got [" + iv.startHour()
                            + ", " + iv.endHour() + ")");
        }
        if (iv.endHour() > horizonHours) {
            throw new InvalidScenarioException(Reason.WINDOW_OUT_OF_HORIZON,
                    "window [" + iv.startHour() + ", " + iv.endHour()
                            + ") exceeds horizon " + horizonHours);
        }
        boolean effectOk = switch (iv.type()) {
            case SOCIAL_DISTANCING, MASKING -> iv.effect() > 0.0 && iv.effect() <= 1.0;
            case TREATMENT -> iv.effect() > 0.0 && iv.effect() < 1.0;
        };
        if (!effectOk || Double.isNaN(iv.effect())) {
            throw new InvalidScenarioException(Reason.INVALID_EFFECT,
                    "effect out of range for " + iv.type() + ": " + iv.effect());
        }
    }

    private static void rejectSameTypeOverlaps(ScenarioConfig config) {
        var list = config.interventions();
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                Intervention a = list.get(i);
                Intervention b = list.get(j);
                if (a.type() == b.type()
                        && a.startHour() < b.endHour()
                        && b.startHour() < a.endHour()) {
                    throw new InvalidScenarioException(Reason.CONFLICTING_INTERVENTIONS,
                            "overlapping windows for type " + a.type() + ": [" + a.startHour()
                                    + ", " + a.endHour() + ") vs [" + b.startHour() + ", "
                                    + b.endHour() + ")");
                }
            }
        }
    }
}
