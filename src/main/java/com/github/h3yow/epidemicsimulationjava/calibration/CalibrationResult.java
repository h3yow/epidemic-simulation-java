package com.github.h3yow.epidemicsimulationjava.calibration;

/**
 * Outcome of a calibration request. Exactly one of success/failure is represented:
 * a failed result carries a non-null reason and no estimates.
 *
 * @param success          true if an estimate was produced
 * @param failureReason    non-null iff !success
 * @param detailReason     machine-distinguishable sub-reason; for RESOURCE_LIMIT_EXCEEDED
 *                         this is the violated capacity dimension name; otherwise null
 * @param message          human-readable explanation
 * @param estimatedBeta    point estimate (NaN on failure)
 * @param betaLower95      lower 95% confidence bound (NaN on failure)
 * @param betaUpper95      upper 95% confidence bound (NaN on failure)
 * @param objectiveValue   SSE at the optimum (NaN on failure)
 * @param noiseSigma       residual noise level (infected counts per hour) used to scale the
 *                         profile-likelihood interval; reflects observation noise and any
 *                         remaining Monte Carlo noise (NaN on failure)
 * @param evaluationsUsed  number of objective evaluations consumed
 */
public record CalibrationResult(
        boolean success,
        CalibrationFailureReason failureReason,
        String detailReason,
        String message,
        double estimatedBeta,
        double betaLower95,
        double betaUpper95,
        double objectiveValue,
        double noiseSigma,
        int evaluationsUsed) {

    public static CalibrationResult failure(CalibrationFailureReason reason, String message) {
        return failure(reason, null, message);
    }

    public static CalibrationResult failure(CalibrationFailureReason reason, String detail, String message) {
        return new CalibrationResult(false, reason, detail, message,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);
    }

    public static CalibrationResult success(double beta, double lo, double hi, double sse,
                                            double noiseSigma, int evals) {
        return new CalibrationResult(true, null, null, "OK", beta, lo, hi, sse, noiseSigma, evals);
    }
}
