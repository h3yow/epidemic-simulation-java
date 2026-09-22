package com.github.h3yow.epidemicsimulationjava.model;

/** Thrown when a scenario configuration is illegal or self-contradictory. */
public class InvalidScenarioException extends RuntimeException {

    private final Reason reason;

    public enum Reason {
        INVALID_POPULATION,
        INVALID_INITIAL_INFECTED,
        INVALID_RATE,
        INVALID_HORIZON,
        INVALID_WINDOW,
        WINDOW_OUT_OF_HORIZON,
        INVALID_EFFECT,
        CONFLICTING_INTERVENTIONS
    }

    public InvalidScenarioException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
