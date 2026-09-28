package com.github.h3yow.epidemicsimulationjava.calibration;

import com.github.h3yow.epidemicsimulationjava.batch.AggregateResult;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRunner;
import com.github.h3yow.epidemicsimulationjava.capacity.CapacityExceededException;
import com.github.h3yow.epidemicsimulationjava.capacity.CapacityGuard;
import com.github.h3yow.epidemicsimulationjava.model.InvalidScenarioException;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioValidator;
import com.github.h3yow.epidemicsimulationjava.simulation.ReplicationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Calibrates the transmission rate beta against an observed infectious curve.
 *
 * <p>Objective: SSE between the observed hourly infectious counts and the mean
 * simulated curve. Every objective evaluation uses the same master seed, so the
 * objective is a deterministic function of beta and the whole calibration is
 * reproducible for a given request and independent of parallelism (the mean
 * curve is aggregated in replication-index order by {@link BatchRunner}).
 *
 * <p>Optimisation: coarse grid over [betaLower, betaUpper], then golden-section
 * refinement inside the best grid bracket, bounded by maxEvaluations.
 *
 * <p>Uncertainty under noisy observations: a profile-likelihood interval on the
 * SSE surface, {@code SSE(beta) = SSE(beta*) + q * sigma2}, q = 3.84 (95%, one
 * parameter). The residual variance sigma2 is the larger of the observation
 * residual SSE/(n-1) and the Monte Carlo noise floor of the mean curve
 * estimated from across-replication variance at the optimum, so stochastic
 * simulation noise can never collapse the interval to a point. Bounds are found
 * by deterministic geometric expansion plus fixed-count bisection, which is
 * parallelism-independent.
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
    /** Relative tolerance on beta for the golden-section refinement. */
    static final double BETA_REL_TOLERANCE = 1e-6;
    /** 95% chi-square threshold with one degree of freedom. */
    static final double PROFILE_Q = 3.841458820694124;
    /** Deterministic bisection depth for each profile bound. */
    static final int BISECTION_STEPS = 12;
    /** Maximum geometric expansion steps when locating a profile crossing. */
    static final int EXPANSION_STEPS = 40;

    private final BatchRunner batchRunner;
    private final CapacityGuard capacityGuard;

    /** Used by Spring: enforces the externally configured capacity limits. */
    @Autowired
    public Calibrator(BatchRunner batchRunner, CapacityGuard capacityGuard) {
        this.batchRunner = batchRunner;
        this.capacityGuard = capacityGuard;
    }

    /** Convenience constructor for tests with default capacity limits. */
    public Calibrator(BatchRunner batchRunner) {
        this(batchRunner, new CapacityGuard());
    }

    /**
     * Runs the calibration. Never throws for expected failure modes; instead
     * returns a result whose failureReason classifies the problem.
     */
    public CalibrationResult calibrate(CalibrationRequest request) {
        log.info("calibration start: masterSeed={} parallelism={} replications={} "
                        + "observations={} bounds=[{}, {}] maxEvaluations={}",
                request == null ? 0 : request.masterSeed(),
                request == null ? 0 : request.parallelism(),
                request == null ? 0 : request.replications(),
                request == null || request.observedInfected() == null ? 0
                        : request.observedInfected().length,
                request == null ? Double.NaN : request.betaLower(),
                request == null ? Double.NaN : request.betaUpper(),
                request == null ? 0 : request.maxEvaluations());

        CalibrationResult precheck = precheck(request);
        if (precheck != null) {
            log.warn("calibration rejected: reason={} detail={} message={}",
                    precheck.failureReason(), precheck.detailReason(), precheck.message());
            return precheck;
        }

        double[] observed = request.observedInfected();
        int n = observed.length;
        int[] evalCounter = {0};
        int maxEvals = request.maxEvaluations();

        // Deterministic objective: same master seed for every evaluation.
        java.util.function.DoubleUnaryOperator objective = beta -> {
            evalCounter[0]++;
            return evaluate(request, observed, beta).sse();
        };

        // --- Coarse grid search ---
        double lower = request.betaLower();
        double upper = request.betaUpper();
        double bestBeta = Double.NaN;
        double bestSse = Double.POSITIVE_INFINITY;
        int bestIdx = -1;
        for (int g = 0; g < GRID_POINTS; g++) {
            double beta = lower + (upper - lower) * g / (GRID_POINTS - 1.0);
            double sse = objective.applyAsDouble(beta);
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
            return nonConvergence(evalCounter[0],
                    "optimiser exhausted evaluation budget " + maxEvals + " without converging");
        }
        // Optimum pinned near either search bound: the data push beta monotonically
        // towards a limit, so it is not identifiable. The guard distance is 1% of
        // the search range (and at least the bracket tolerance).
        double boundTolerance = Math.max(0.01 * (upper - lower),
                BETA_REL_TOLERANCE * (upper - lower));
        if (!(bestBeta > lower + boundTolerance) || !(bestBeta < upper - boundTolerance)) {
            return unidentifiable(evalCounter[0],
                    "optimum is pinned at a parameter bound [" + lower + ", " + upper
                            + "] (bestBeta=" + bestBeta + "); beta is not identifiable from these data");
        }

        // --- Profile-likelihood uncertainty interval, robust to noisy observations ---
        CalibrationResult interval = profileInterval(request, observed, lower, upper,
                bestBeta, n, objective, evalCounter, maxEvals);
        if (!interval.success()) {
            return interval;
        }

        log.info("calibration done: masterSeed={} parallelism={} estimatedBeta={} CI95=[{}, {}] "
                        + "sse={} noiseSigma={} width={} evaluations={}",
                request.masterSeed(), request.parallelism(), interval.estimatedBeta(),
                interval.betaLower95(), interval.betaUpper95(), interval.objectiveValue(),
                interval.noiseSigma(), interval.betaUpper95() - interval.betaLower95(),
                evalCounter[0]);
        return interval;
    }

    /**
     * Builds the 95% profile-likelihood interval around the optimum.
     *
     * <p>Residual variance is at least the Monte Carlo noise floor of the mean
     * curve (estimated from across-replication variance at the optimum), so
     * stochastic simulation noise cannot shrink the interval to a point.
     */
    private CalibrationResult profileInterval(CalibrationRequest request, double[] observed,
                                              double lower, double upper,
                                              double bestBeta, int n,
                                              java.util.function.DoubleUnaryOperator objective,
                                              int[] evalCounter, int maxEvals) {
        evalCounter[0]++;
        if (evalCounter[0] > maxEvals) {
            return nonConvergence(evalCounter[0],
                    "evaluation budget " + maxEvals
                            + " exhausted before the uncertainty interval could be evaluated");
        }
        EvalOutcome atOptimum = evaluate(request, observed, bestBeta);
        double sseOpt = atOptimum.sse();
        if (!Double.isFinite(sseOpt)) {
            return nonConvergence(evalCounter[0], "objective at the optimum is not finite");
        }
        // Residual SSE floor from Monte Carlo noise of the mean curve:
        // sum_t Var(x_t)/reps, estimated unbiasedly from the replications.
        double residualSse = Math.max(sseOpt, atOptimum.mcSseFloor());
        double sigma2 = residualSse / (n - 1.0);
        if (!(sigma2 > 0.0) || !Double.isFinite(sigma2)) {
            return unidentifiable(evalCounter[0],
                    "residual variance at the optimum is zero; the uncertainty interval is undefined "
                            + "(observations are indistinguishable from a deterministic curve)");
        }
        double threshold = sseOpt + PROFILE_Q * sigma2;
        double noiseSigma = Math.sqrt(sigma2);

        Double lo = crossOnSide(bestBeta, lower, threshold, true, objective, evalCounter, maxEvals);
        if (lo == null) {
            return budgetOrUnidentifiable(evalCounter, maxEvals,
                    "profile SSE does not reach the 95% threshold before the lower bound "
                            + lower + "; beta is not identifiable from these data");
        }
        Double hi = crossOnSide(bestBeta, upper, threshold, false, objective, evalCounter, maxEvals);
        if (hi == null) {
            return budgetOrUnidentifiable(evalCounter, maxEvals,
                    "profile SSE does not reach the 95% threshold before the upper bound "
                            + upper + "; beta is not identifiable from these data");
        }
        double ciLo = Math.max(lower, lo);
        double ciHi = Math.min(upper, hi);
        if (!(ciLo < bestBeta && bestBeta < ciHi) || ciHi - ciLo <= 0.0) {
            return unidentifiable(evalCounter[0],
                    "profile interval does not bracket the optimum; beta is not identifiable");
        }
        return CalibrationResult.success(bestBeta, ciLo, ciHi, sseOpt, noiseSigma, evalCounter[0]);
    }

    /**
     * Finds the SSE threshold crossing on one side of the optimum.
     *
     * <p>Geometric expansion from the optimum towards the bound brackets the
     * crossing; a fixed number of bisections then resolves it. Returns null if
     * no crossing exists before the bound or the evaluation budget is exhausted.
     */
    private Double crossOnSide(double bestBeta, double bound, double threshold, boolean left,
                               java.util.function.DoubleUnaryOperator objective,
                               int[] evalCounter, int maxEvals) {
        double scale = Math.max(1e-12, Math.abs(bestBeta));
        double inner = bestBeta;
        double step = scale * 1e-5;
        for (int k = 0; k < EXPANSION_STEPS; k++) {
            if (evalCounter[0] >= maxEvals) {
                return null;
            }
            double outer = left ? Math.max(bound, bestBeta - step)
                                : Math.min(bound, bestBeta + step);
            if (outer == inner) {
                // Step too small to move at this precision: grow and retry.
                step *= 2.0;
                continue;
            }
            evalCounter[0]++;
            double sse = objective.applyAsDouble(outer);
            if (!Double.isFinite(sse)) {
                sse = Double.POSITIVE_INFINITY;
            }
            if (sse >= threshold) {
                return bisect(objective, inner, outer, threshold, evalCounter, maxEvals);
            }
            inner = outer;
            if (outer == bound) {
                return null;
            }
            step *= 1.6;
        }
        return null;
    }

    /** Fixed-count bisection with [inner, outer] straddling SSE(.) = threshold. */
    private Double bisect(java.util.function.DoubleUnaryOperator objective,
                          double inner, double outer, double threshold,
                          int[] evalCounter, int maxEvals) {
        double below = inner;
        double crossingOrAbove = outer;
        for (int i = 0; i < BISECTION_STEPS; i++) {
            if (evalCounter[0] >= maxEvals) {
                return null;
            }
            double mid = 0.5 * (below + crossingOrAbove);
            evalCounter[0]++;
            double sse = objective.applyAsDouble(mid);
            if (!Double.isFinite(sse)) {
                sse = Double.POSITIVE_INFINITY;
            }
            if (sse >= threshold) {
                crossingOrAbove = mid;
            } else {
                below = mid;
            }
        }
        return 0.5 * (below + crossingOrAbove);
    }

    private static CalibrationResult budgetOrUnidentifiable(int[] evalCounter, int maxEvals, String message) {
        if (evalCounter[0] >= maxEvals) {
            return nonConvergence(evalCounter[0],
                    "evaluation budget " + maxEvals + " exhausted while locating the uncertainty interval");
        }
        return unidentifiable(evalCounter[0], message);
    }

    private static CalibrationResult nonConvergence(int evals, String message) {
        return new CalibrationResult(false, CalibrationFailureReason.NON_CONVERGENCE, null,
                message, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, evals);
    }

    private static CalibrationResult unidentifiable(int evals, String message) {
        return new CalibrationResult(false, CalibrationFailureReason.PARAMETER_UNIDENTIFIABLE, null,
                message, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, evals);
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
        if (request.replications() < 1 || request.parallelism() < 1 || request.maxEvaluations() < 1) {
            return CalibrationResult.failure(CalibrationFailureReason.PARAMETER_OUT_OF_BOUNDS,
                    "replications, parallelism and maxEvaluations must be >= 1; got replications="
                            + request.replications() + " parallelism=" + request.parallelism()
                            + " maxEvaluations=" + request.maxEvaluations());
        }
        // Unified, externally configurable capacity enforcement before any heavy work.
        try {
            capacityGuard.checkCalibration(request);
        } catch (CapacityExceededException e) {
            return CalibrationResult.failure(CalibrationFailureReason.RESOURCE_LIMIT_EXCEEDED,
                    e.dimension().name(), e.getMessage());
        }
        return null;
    }

    /**
     * Evaluates the mean simulated curve at beta against the observations.
     *
     * @return residual SSE and the Monte Carlo SSE floor of the mean curve
     *         (sum over observed hours of the unbiased Var(mean_t) estimate)
     */
    private EvalOutcome evaluate(CalibrationRequest request, double[] observed, double beta) {
        ScenarioConfig base = request.baseScenario();
        ScenarioConfig scenario = new ScenarioConfig(
                base.population(), base.initialInfected(), beta, base.gamma(),
                base.horizonHours(), base.interventions());
        AggregateResult batch = batchRunner.run(new BatchRequest(
                scenario, request.replications(), request.masterSeed(), request.parallelism(), 0));
        int horizon = scenario.horizonHours();
        int reps = batch.replications();
        int m = Math.min(observed.length, horizon);
        double[] sum = new double[m];
        double[] sumSq = new double[m];
        for (ReplicationResult r : batch.results()) {
            long[] hourly = r.hourlyInfected();
            for (int t = 0; t < m; t++) {
                double v = hourly[t];
                sum[t] += v;
                sumSq[t] += v * v;
            }
        }
        double sse = 0.0;
        double mcSseFloor = 0.0;
        for (int t = 0; t < observed.length; t++) {
            double mean = t < m ? sum[t] / reps : 0.0;
            double diff = mean - observed[t];
            sse += diff * diff;
            if (t < m && reps > 1) {
                // Unbiased Var(x_t) = (sumSq - sum^2/reps) / (reps - 1);
                // Var(mean_t) = Var(x_t) / reps.
                double variance = (sumSq[t] - sum[t] * sum[t] / reps) / (reps - 1.0);
                mcSseFloor += Math.max(0.0, variance) / reps;
            }
        }
        return new EvalOutcome(sse, mcSseFloor);
    }

    /** Internal evaluation outcome: residual SSE and the Monte Carlo SSE floor. */
    private record EvalOutcome(double sse, double mcSseFloor) {
    }
}
