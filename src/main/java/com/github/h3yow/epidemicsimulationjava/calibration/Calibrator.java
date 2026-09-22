package com.github.h3yow.epidemicsimulationjava.calibration;

import com.github.h3yow.epidemicsimulationjava.batch.AggregateResult;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRunner;
import com.github.h3yow.epidemicsimulationjava.model.InvalidScenarioException;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioValidator;
import com.github.h3yow.epidemicsimulationjava.simulation.ReplicationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Calibrates the transmission rate beta against an observed infectious curve.
 *
 * <p>Objective: SSE between the observed hourly infectious counts and the mean
 * simulated curve. Every objective evaluation uses the same master seed, so the
 * objective is a deterministic function of beta and the whole calibration is
 * reproducible for a given request.
 *
 * <p>Optimisation: coarse grid over [betaLower, betaUpper], then golden-section
 * refinement inside the best grid bracket, bounded by maxEvaluations.
 *
 * <p>Uncertainty: curvature of SSE at the optimum (normal approximation):
 * se(beta) = sqrt(2 * sigma2 / SSE''), sigma2 = SSE / (n - 1); 95% CI = beta ± 1.96 se.
 *
 * <p>Failure modes are reported with machine-distinguishable reasons, never as
 * empty or deceptively successful results.
 */
@Service
public class Calibrator {

    private static final Logger log = LoggerFactory.getLogger(Calibrator.class);

    /** Minimum number of observation points needed to identify beta. */
    static final int MIN_OBSERVATIONS = 5;
    /** Grid resolution for the coarse search. */
    static final int GRID_POINTS = 25;
    /** Hard caps protecting local resources. */
    static final int MAX_EVALUATIONS = 500;
    static final int MAX_REPLICATIONS = 1_000;
    static final long MAX_TOTAL_SIMULATED_HOURS = 20_000_000L;
    /** Relative tolerance on beta for the golden-section refinement. */
    static final double BETA_REL_TOLERANCE = 1e-6;

    private final BatchRunner batchRunner;

    public Calibrator(BatchRunner batchRunner) {
        this.batchRunner = batchRunner;
    }

    /**
     * Runs the calibration. Never throws for expected failure modes; instead
     * returns a result whose failureReason classifies the problem.
     */
    public CalibrationResult calibrate(CalibrationRequest request) {
        log.info("calibration start: masterSeed={} parallelism={} replications={} "
                        + "observations={} bounds=[{}, {}] maxEvaluations={}",
                request.masterSeed(), request.parallelism(), request.replications(),
                request.observedInfected() == null ? 0 : request.observedInfected().length,
                request.betaLower(), request.betaUpper(), request.maxEvaluations());

        CalibrationResult precheck = precheck(request);
        if (precheck != null) {
            log.warn("calibration rejected: reason={} message={}", precheck.failureReason(), precheck.message());
            return precheck;
        }

        double[] observed = request.observedInfected();
        int n = observed.length;
        int[] evalCounter = {0};
        int maxEvals = request.maxEvaluations();

        // Deterministic objective: same master seed for every evaluation.
        java.util.function.DoubleUnaryOperator objective = beta -> {
            evalCounter[0]++;
            return sse(request, observed, beta);
        };

        // --- Coarse grid search ---
        double lower = request.betaLower();
        double upper = request.betaUpper();
        double bestBeta = Double.NaN;
        double bestSse = Double.POSITIVE_INFINITY;
        int bestIdx = -1;
        double[] gridSse = new double[GRID_POINTS];
        for (int g = 0; g < GRID_POINTS; g++) {
            double beta = lower + (upper - lower) * g / (GRID_POINTS - 1.0);
            double sse = objective.applyAsDouble(beta);
            gridSse[g] = sse;
            if (sse < bestSse) {
                bestSse = sse;
                bestBeta = beta;
                bestIdx = g;
            }
        }
        if (!Double.isFinite(bestSse)) {
            return CalibrationResult.failure(CalibrationFailureReason.NON_CONVERGENCE,
                    "objective is not finite anywhere on the grid");
        }

        // --- Golden-section refinement inside the best grid bracket ---
        double a = bestIdx == 0 ? lower : lower + (upper - lower) * (bestIdx - 1) / (GRID_POINTS - 1.0);
        double b = bestIdx == GRID_POINTS - 1 ? upper : lower + (upper - lower) * (bestIdx + 1) / (GRID_POINTS - 1.0);
        boolean converged = false;
        double invPhi = (Math.sqrt(5.0) - 1.0) / 2.0;
        double c = b - invPhi * (b - a);
        double d = a + invPhi * (b - a);
        double fc = objective.applyAsDouble(c);
        double fd = objective.applyAsDouble(d);
        while (evalCounter[0] < maxEvals) {
            if (Math.abs(b - a) <= BETA_REL_TOLERANCE * Math.max(1.0, Math.abs(bestBeta))) {
                converged = true;
                break;
            }
            if (fc < fd) {
                b = d;
                d = c;
                fd = fc;
                c = b - invPhi * (b - a);
                fc = objective.applyAsDouble(c);
            } else {
                a = c;
                c = d;
                fc = fd;
                d = a + invPhi * (b - a);
                fd = objective.applyAsDouble(d);
            }
            double mid = 0.5 * (a + b);
            double midSse = Math.min(fc, fd);
            if (midSse < bestSse) {
                bestSse = midSse;
                bestBeta = mid;
            }
        }
        if (!converged && Math.abs(b - a) > BETA_REL_TOLERANCE * Math.max(1.0, Math.abs(bestBeta))) {
            return new CalibrationResult(false, CalibrationFailureReason.NON_CONVERGENCE,
                    "optimiser exhausted evaluation budget " + maxEvals + " without converging",
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, evalCounter[0]);
        }

        // --- Uncertainty from SSE curvature at the optimum ---
        double h = Math.max(1e-4, 1e-4 * bestBeta);
        double ssePlus = objective.applyAsDouble(bestBeta + h);
        double sseMinus = objective.applyAsDouble(bestBeta - h);
        double curvature = (ssePlus - 2.0 * bestSse + sseMinus) / (h * h);
        if (!(curvature > 0.0) || !Double.isFinite(curvature)) {
            return new CalibrationResult(false, CalibrationFailureReason.NON_CONVERGENCE,
                    "SSE curvature at optimum is not positive; beta is not identifiable from these data",
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, evalCounter[0]);
        }
        double sigma2 = bestSse / (n - 1.0);
        double se = Math.sqrt(2.0 * sigma2 / curvature);
        double ciLo = Math.max(lower, bestBeta - 1.96 * se);
        double ciHi = Math.min(upper, bestBeta + 1.96 * se);

        CalibrationResult result = CalibrationResult.success(bestBeta, ciLo, ciHi, bestSse, evalCounter[0]);
        log.info("calibration done: masterSeed={} parallelism={} estimatedBeta={} CI95=[{}, {}] "
                        + "sse={} evaluations={}",
                request.masterSeed(), request.parallelism(), bestBeta, ciLo, ciHi, bestSse, evalCounter[0]);
        return result;
    }

    /** @return a failure result if the request must be rejected, else null */
    private CalibrationResult precheck(CalibrationRequest request) {
        if (request == null || request.baseScenario() == null) {
            return CalibrationResult.failure(CalibrationFailureReason.PARAMETER_OUT_OF_BOUNDS,
                    "request and baseScenario must not be null");
        }
        try {
            ScenarioValidator.validate(request.baseScenario());
        } catch (InvalidScenarioException e) {
            return CalibrationResult.failure(CalibrationFailureReason.PARAMETER_OUT_OF_BOUNDS,
                    "invalid base scenario: " + e.getMessage());
        }
        if (!(request.betaLower() > 0.0) || !(request.betaUpper() > request.betaLower())
                || Double.isNaN(request.betaLower()) || Double.isNaN(request.betaUpper())) {
            return CalibrationResult.failure(CalibrationFailureReason.PARAMETER_OUT_OF_BOUNDS,
                    "bounds must satisfy 0 < betaLower < betaUpper, got [" + request.betaLower()
                            + ", " + request.betaUpper() + "]");
        }
        if (request.observedInfected() == null
                || request.observedInfected().length < MIN_OBSERVATIONS) {
            return CalibrationResult.failure(CalibrationFailureReason.INSUFFICIENT_DATA,
                    "need at least " + MIN_OBSERVATIONS + " observation points, got "
                            + (request.observedInfected() == null ? 0 : request.observedInfected().length));
        }
        if (request.observedInfected().length > request.baseScenario().horizonHours()) {
            return CalibrationResult.failure(CalibrationFailureReason.INSUFFICIENT_DATA,
                    "observation length " + request.observedInfected().length
                            + " exceeds scenario horizon " + request.baseScenario().horizonHours());
        }
        for (double v : request.observedInfected()) {
            if (Double.isNaN(v) || v < 0.0) {
                return CalibrationResult.failure(CalibrationFailureReason.INSUFFICIENT_DATA,
                        "observations must be non-negative finite counts, got " + v);
            }
        }
        if (request.replications() < 1 || request.replications() > MAX_REPLICATIONS
                || request.maxEvaluations() < 1 || request.maxEvaluations() > MAX_EVALUATIONS
                || request.parallelism() < 1) {
            return CalibrationResult.failure(CalibrationFailureReason.RESOURCE_LIMIT_EXCEEDED,
                    "replications must be in [1, " + MAX_REPLICATIONS + "], evaluations in [1, "
                            + MAX_EVALUATIONS + "], parallelism >= 1; got replications="
                            + request.replications() + " maxEvaluations=" + request.maxEvaluations()
                            + " parallelism=" + request.parallelism());
        }
        long totalHours = (long) request.maxEvaluations() * request.replications()
                * request.baseScenario().horizonHours();
        if (totalHours > MAX_TOTAL_SIMULATED_HOURS) {
            return CalibrationResult.failure(CalibrationFailureReason.RESOURCE_LIMIT_EXCEEDED,
                    "requested work " + totalHours + " simulated hours exceeds budget "
                            + MAX_TOTAL_SIMULATED_HOURS
                            + "; reduce maxEvaluations, replications or horizon");
        }
        return null;
    }

    /** SSE between the observed curve and the mean simulated curve at the given beta. */
    private double sse(CalibrationRequest request, double[] observed, double beta) {
        ScenarioConfig base = request.baseScenario();
        ScenarioConfig scenario = new ScenarioConfig(
                base.population(), base.initialInfected(), beta, base.gamma(),
                base.horizonHours(), base.interventions());
        AggregateResult batch = batchRunner.run(new BatchRequest(
                scenario, request.replications(), request.masterSeed(), request.parallelism(), 0));
        int horizon = scenario.horizonHours();
        double[] meanCurve = new double[observed.length];
        for (ReplicationResult r : batch.results()) {
            long[] hourly = r.hourlyInfected();
            for (int t = 0; t < observed.length && t < horizon; t++) {
                meanCurve[t] += hourly[t];
            }
        }
        double sse = 0.0;
        int reps = batch.replications();
        for (int t = 0; t < observed.length; t++) {
            double diff = meanCurve[t] / reps - observed[t];
            sse += diff * diff;
        }
        return sse;
    }
}
