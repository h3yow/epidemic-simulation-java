package com.github.h3yow.epidemicsimulationjava.model;

/** 可区分的失败原因分类。 */
public enum FailureReason {
    INVALID_SCENARIO,
    INVALID_INTERVENTION,
    CONFLICTING_INTERVENTIONS,
    INVALID_CALIBRATION_REQUEST,
    INSUFFICIENT_DATA,
    NO_CONVERGENCE,
    RESOURCE_LIMIT_EXCEEDED,
    CANCELLED,
    TIMEOUT
}
