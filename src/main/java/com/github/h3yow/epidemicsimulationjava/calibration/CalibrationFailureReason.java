package com.github.h3yow.epidemicsimulationjava.calibration;

/** Machine-distinguishable reasons why a calibration request can fail. */
public enum CalibrationFailureReason {
    /** Declared parameter bounds or initial guess are invalid or inconsistent. */
    PARAMETER_OUT_OF_BOUNDS,
    /** Too few usable observation points to identify the parameter. */
    INSUFFICIENT_DATA,
    /** Optimiser exhausted its iteration budget without a stable optimum. */
    NON_CONVERGENCE,
    /** Requested work exceeds the configured resource budget; rejected before running. */
    RESOURCE_LIMIT_EXCEEDED
}
