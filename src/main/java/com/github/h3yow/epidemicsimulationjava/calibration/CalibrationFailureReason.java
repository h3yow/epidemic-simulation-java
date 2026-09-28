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
    RESOURCE_LIMIT_EXCEEDED,
    /**
     * The data do not identify beta: the profile SSE never rises far enough
     * above its minimum within the parameter bounds to bound the estimate.
     */
    PARAMETER_UNIDENTIFIABLE,
    /**
     * The point estimate exists, but a meaningful uncertainty interval cannot
     * be built from the supplied observations (e.g. no usable residual
     * information or budget exhausted while tracing the interval).
     */
    UNCERTAINTY_NOT_QUANTIFIABLE
}
