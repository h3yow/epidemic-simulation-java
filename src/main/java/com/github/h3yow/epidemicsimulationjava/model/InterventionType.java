package com.github.h3yow.epidemicsimulationjava.model;

/** Types of non-pharmaceutical interventions supported by the model. */
public enum InterventionType {
    /** Reduces transmission rate (beta) by a multiplier. */
    SOCIAL_DISTANCING,
    /** Reduces transmission rate (beta) by a multiplier. */
    MASKING,
    /** Increases recovery rate (gamma) by an additive increment, e.g. better treatment. */
    TREATMENT
}
