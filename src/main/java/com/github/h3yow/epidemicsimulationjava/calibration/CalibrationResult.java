package com.github.h3yow.epidemicsimulationjava.calibration;

import com.github.h3yow.epidemicsimulationjava.capacity.CapacityDimension;

/**
 * Outcome of a calibration request. Exactly one of success/failure is represented:
 * a failed result carries a non-null reason and no estimates.
 *
 * @param success          true if an estimate was produced
 * @param failureReason    non-null iff !success
 * @param message          human-readable explanation
 * @param estimatedBeta    point estimate (NaN on failure)
 * @param betaLower95      lower 95% confidence bound (NaN on failure)
 * @param betaUpper95      upper 95% confidence bound (NaN on failure)
 * @param objectiveValue   SSE at the optimum (NaN on failure)
 * @param evaluationsUsed  number of objective evaluations consumed
 * @param capacityReason   stable machine-readable code naming the exceeded
 *                         capacity dimension when failureReason is
 *                         RESOURCE_LIMIT_EXCEEDED; null otherwise
 */
public record CalibrationResult(
        boolean success,
        CalibrationFailureReason failureReason,
        String message,
        double estimatedBeta,
        double betaLower95,
        double betaUpper95,
        double objectiveValue,
        int evaluationsUsed,
        String capacityReason) {

    public static CalibrationResult failure(CalibrationFailureReason reason, String message) {
        return new CalibrationResult(false, reason, message,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, null);
    }

    /** Capacity rejection carrying the specific resource-dimension code. */
    public static CalibrationResult capacityFailure(CapacityDimension dimension, String message) {
        return new CalibrationResult(false, CalibrationFailureReason.RESOURCE_LIMIT_EXCEEDED,
                message, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, dimension.code());
    }

    public static CalibrationResult failure(CalibrationFailureReason reason, String message, int evals) {
        return new CalibrationResult(false, reason, message,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, evals, null);
    }

    public static CalibrationResult success(double beta, double lo, double hi, double sse, int evals) {
        return new CalibrationResult(true, null, "OK", beta, lo, hi, sse, evals, null);
    }
}
