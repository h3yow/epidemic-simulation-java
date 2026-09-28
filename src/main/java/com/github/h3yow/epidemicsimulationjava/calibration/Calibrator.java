package com.github.h3yow.epidemicsimulationjava.calibration;

import com.github.h3yow.epidemicsimulationjava.batch.AggregateResult;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRunner;
import com.github.h3yow.epidemicsimulationjava.capacity.CapacityExceededException;
import com.github.h3yow.epidemicsimulationjava.capacity.CapacityGuard;
import com.github.h3yow.epidemicsimulationjava.capacity.CapacityLimits;
import com.github.h3yow.epidemicsimulationjava.model.InvalidScenarioException;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioValidator;
import com.github.h3yow.epidemicsimulationjava.simulation.ReplicationResult;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Calibrates the transmission rate beta against an observed infectious curve.
 *
 * <p>Objective: SSE between the observed hourly infectious counts and the mean
 * simulated curve. Every objective evaluation uses the same master seed, so the
 * objective is a deterministic function of beta: the point estimate and the
 * uncertainty interval are reproducible and independent of parallelism.
 *
 * <p>Optimisation: coarse grid over [betaLower, betaUpper], then golden-section
 * refinement inside the best grid bracket, bounded by maxEvaluations.
 *
 * <p>Uncertainty under noisy observations: a profile-likelihood interval, not a
 * curvature/normal approximation. At the optimum we estimate the total pointwise
 * variance actually present in the residuals:
 * the larger of the observed residual variance and the Monte-Carlo noise floor
 * (variance of the simulated mean across the replications). The 95% threshold is
 * SSE(beta*) + sigma2 * chi2_0.95(1); the interval endpoints are the beta values
 * on either side of the optimum where the profile SSE crosses that threshold.
 * The MC noise floor keeps the interval from collapsing to a point even with
 * essentially noiseless observations, while genuinely noisy data widen it to
 * reflect the real fluctuation of beta.
 *
 * <p>If the profile never rises above the threshold before hitting a parameter
 * bound, beta is not identifiable and {@link CalibrationFailureReason#PARAMETER_UNIDENTIFIABLE}
 * is returned; a degenerate or untraceable interval yields
 * {@link CalibrationFailureReason#UNCERTAINTY_NOT_QUANTIFIABLE}. Failure modes
 * are reported with machine-distinguishable reasons, never as empty or
 * deceptively successful results.
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
    /** Relative tolerance on beta when bisecting the profile-SSE crossing. */
    static final double CROSSING_REL_TOLERANCE = 1e-5;
    /** chi-square 95% quantile with one degree of freedom. */
    static final double CHI2_95_DF1 = 3.841458820694124;
    /** Absolute variance floor so threshold arithmetic stays finite. */
    static final double VARIANCE_FLOOR = 1e-12;
    /** Minimum relative half-width of an accepted interval. */
    static final double MIN_INTERVAL_REL_HALF_WIDTH = 1e-8;

    /** Minimum relative SSE rise (in units of the 95% threshold increment) that identifies beta. */
    static final double IDENTIFIABILITY_RISE_MULTIPLE = 2.0;

    private final BatchRunner batchRunner;
    private final CapacityGuard capacityGuard;

    /** Spring constructor: capacity enforced against configured ceilings. */
    @org.springframework.beans.factory.annotation.Autowired
    public Calibrator(BatchRunner batchRunner, CapacityGuard capacityGuard) {
        this.batchRunner = batchRunner;
        this.capacityGuard = capacityGuard;
    }

    /** Convenience constructor with built-in default ceilings (used by direct unit tests). */
    public Calibrator(BatchRunner batchRunner) {
        this(batchRunner, new CapacityGuard(CapacityLimits.defaults()));
    }

    /**
     * Runs the calibration. Never throws for expected failure modes; instead
     * returns a result whose failureReason classifies the problem.
     */
    public CalibrationResult calibrate(CalibrationRequest request) {
        int obsCount = request == null || request.observedInfected() == null
                ? 0 : request.observedInfected().length;
        log.info("calibration start: masterSeed={} parallelism={} replications={} "
                        + "observations={} bounds=[{}, {}] maxEvaluations={}",
                request == null ? 0L : request.masterSeed(),
                request == null ? 0 : request.parallelism(),
                request == null ? 0 : request.replications(),
                obsCount,
                request == null ? Double.NaN : request.betaLower(),
                request == null ? Double.NaN : request.betaUpper(),
                request == null ? 0 : request.maxEvaluations());

        CalibrationResult precheck = precheck(request);
        if (precheck != null) {
            log.warn("calibration rejected: reason={} message={}", precheck.failureReason(),
                    precheck.message());
            return precheck;
        }

        double[] observed = request.observedInfected();
        int n = observed.length;
        int maxEvals = request.maxEvaluations();

        Map<Double, Eval> cache = new HashMap<>();
        int[] evalCounter = {0};

        java.util.function.DoubleFunction<Eval> evaluate = beta -> cache.computeIfAbsent(beta, b -> {
            evalCounter[0]++;
            return evaluate(request, observed, b);
        });

        // --- Coarse grid search ---
        double lower = request.betaLower();
        double upper = request.betaUpper();
        double bestBeta = Double.NaN;
        double bestSse = Double.POSITIVE_INFINITY;
        int bestIdx = -1;
        for (int g = 0; g < GRID_POINTS; g++) {
            double beta = lower + (upper - lower) * g / (GRID_POINTS - 1.0);
            Eval eval = evaluate.apply(beta);
            if (eval.sse() < bestSse) {
                bestSse = eval.sse();
                bestBeta = beta;
                bestIdx = g;
            }
        }
        if (!Double.isFinite(bestSse)) {
            return CalibrationResult.failure(CalibrationFailureReason.NON_CONVERGENCE,
                    "objective is not finite anywhere on the grid", evalCounter[0]);
        }

        // --- Golden-section refinement inside the best grid bracket ---
        double a = bestIdx == 0 ? lower
                : lower + (upper - lower) * (bestIdx - 1) / (GRID_POINTS - 1.0);
        double b = bestIdx == GRID_POINTS - 1 ? upper
                : lower + (upper - lower) * (bestIdx + 1) / (GRID_POINTS - 1.0);
        boolean converged = false;
        double invPhi = (Math.sqrt(5.0) - 1.0) / 2.0;
        double c = b - invPhi * (b - a);
        double d = a + invPhi * (b - a);
        double fc = evaluate.apply(c).sse();
        double fd = evaluate.apply(d).sse();
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
                fc = evaluate.apply(c).sse();
            } else {
                a = c;
                c = d;
                fc = fd;
                d = a + invPhi * (b - a);
                fd = evaluate.apply(d).sse();
            }
            double mid = 0.5 * (a + b);
            double midSse = Math.min(fc, fd);
            if (midSse < bestSse) {
                bestSse = midSse;
                bestBeta = mid;
            }
        }
        // Re-read the optimum evaluation (cached if bestBeta was a grid point).
        Eval best = evaluate.apply(bestBeta);
        bestSse = best.sse();
        if (!converged && Math.abs(b - a) > BETA_REL_TOLERANCE * Math.max(1.0, Math.abs(bestBeta))) {
            return CalibrationResult.failure(CalibrationFailureReason.NON_CONVERGENCE,
                    "optimiser exhausted evaluation budget " + maxEvals + " without converging",
                    evalCounter[0]);
        }
        // --- Profile-likelihood interval with a Monte-Carlo noise floor ---
        double mcVarMean = best.mcSseContribution() / n;
        double residualVar = bestSse / n;
        double sigma2 = Math.max(VARIANCE_FLOOR, Math.max(residualVar, mcVarMean));
        double threshold = bestSse + sigma2 * CHI2_95_DF1;

        // Identifiability requires the profile SSE to rise clearly above the
        // minimum on BOTH sides within the parameter bounds:
        //   - an optimum sitting on (or within ~one grid step of) a parameter
        //     bound means nothing rises on that side, so beta is unbounded;
        //   - at coarse-grid vertices two steps away, the rise on either side
        //     must exceed Monte-Carlo chatter (a small multiple of the
        //     profile threshold increment 3.84 * sigma2).
        // Informative data, even noisy, show rises of orders of magnitude and
        // pass; flat/uninformative observations fail with a distinct reason
        // instead of a deceptive point-like interval.
        double thresholdIncrement = sigma2 * CHI2_95_DF1;
        double gridStep = (upper - lower) / (GRID_POINTS - 1.0);
        boolean atLowerBound = bestBeta <= lower + 1.5 * gridStep;
        boolean atUpperBound = bestBeta >= upper - 1.5 * gridStep;
        double leftProbe = bestIdx >= 2
                ? lower + (upper - lower) * (bestIdx - 2) / (GRID_POINTS - 1.0)
                : lower;
        double rightProbe = bestIdx <= GRID_POINTS - 3
                ? lower + (upper - lower) * (bestIdx + 2) / (GRID_POINTS - 1.0)
                : upper;
        double riseLeft = evaluate.apply(leftProbe).sse() - bestSse;
        double riseRight = evaluate.apply(rightProbe).sse() - bestSse;
        boolean risesLeft = !atLowerBound
                && riseLeft > IDENTIFIABILITY_RISE_MULTIPLE * thresholdIncrement;
        boolean risesRight = !atUpperBound
                && riseRight > IDENTIFIABILITY_RISE_MULTIPLE * thresholdIncrement;
        if (!risesLeft || !risesRight) {
            return CalibrationResult.failure(CalibrationFailureReason.PARAMETER_UNIDENTIFIABLE,
                    "profile likelihood does not bound beta: atLowerBound=" + atLowerBound
                            + " atUpperBound=" + atUpperBound + " riseNearOptimum=["
                            + riseLeft + ", " + riseRight + "] noiseThreshold="
                            + IDENTIFIABILITY_RISE_MULTIPLE * thresholdIncrement + " bestSse="
                            + bestSse,
                    evalCounter[0]);
        }

        double ciLo = findCrossing(evaluate, evalCounter, maxEvals,
                bestBeta, threshold, true, lower, upper, cache);
        if (evalCounter[0] >= maxEvals) {
            return CalibrationResult.failure(CalibrationFailureReason.UNCERTAINTY_NOT_QUANTIFIABLE,
                    "evaluation budget " + maxEvals
                            + " exhausted while tracing the lower uncertainty bound",
                    evalCounter[0]);
        }
        if (Double.isNaN(ciLo)) {
            return CalibrationResult.failure(CalibrationFailureReason.PARAMETER_UNIDENTIFIABLE,
                    "profile SSE stays within the 95% threshold down to betaLower="
                            + lower + "; beta is not bounded from below by these data",
                    evalCounter[0]);
        }
        double ciHi = findCrossing(evaluate, evalCounter, maxEvals,
                bestBeta, threshold, false, lower, upper, cache);
        if (evalCounter[0] >= maxEvals) {
            return CalibrationResult.failure(CalibrationFailureReason.UNCERTAINTY_NOT_QUANTIFIABLE,
                    "evaluation budget " + maxEvals
                            + " exhausted while tracing the upper uncertainty bound",
                    evalCounter[0]);
        }
        if (Double.isNaN(ciHi)) {
            return CalibrationResult.failure(CalibrationFailureReason.PARAMETER_UNIDENTIFIABLE,
                    "profile SSE stays within the 95% threshold up to betaUpper="
                            + upper + "; beta is not bounded from above by these data",
                    evalCounter[0]);
        }
        double halfWidth = 0.5 * (ciHi - ciLo);
        if (!(halfWidth > MIN_INTERVAL_REL_HALF_WIDTH * Math.max(1.0, Math.abs(bestBeta)))
                || ciLo >= bestBeta || ciHi <= bestBeta) {
            return CalibrationResult.failure(CalibrationFailureReason.UNCERTAINTY_NOT_QUANTIFIABLE,
                    "uncertainty interval degenerated (half-width " + halfWidth
                            + "); observations carry no usable fluctuation information",
                    evalCounter[0]);
        }

        CalibrationResult result = CalibrationResult.success(bestBeta, ciLo, ciHi, bestSse,
                evalCounter[0]);
        log.info("calibration done: masterSeed={} parallelism={} estimatedBeta={} CI95=[{}, {}] "
                        + "sse={} sigma2={} mcNoiseVar={} evaluations={}",
                request.masterSeed(), request.parallelism(), bestBeta, ciLo, ciHi, bestSse,
                sigma2, mcVarMean, evalCounter[0]);
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
                            + (request.observedInfected() == null ? 0
                            : request.observedInfected().length));
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
        if (request.replications() < 1 || request.maxEvaluations() < 1
                || request.parallelism() < 1) {
            return CalibrationResult.failure(CalibrationFailureReason.RESOURCE_LIMIT_EXCEEDED,
                    "replications, maxEvaluations and parallelism must be >= 1; got replications="
                            + request.replications() + " maxEvaluations=" + request.maxEvaluations()
                            + " parallelism=" + request.parallelism());
        }
        try {
            capacityGuard.checkCalibration(request.baseScenario().population(),
                    request.baseScenario().horizonHours(), request.replications(),
                    request.parallelism(), request.maxEvaluations(),
                    request.observedInfected().length);
        } catch (CapacityExceededException e) {
            return CalibrationResult.capacityFailure(e.dimension(), e.getMessage());
        }
        return null;
    }

    /**
     * Evaluates one objective value.
     *
     * @return SSE plus the Monte-Carlo noise contribution to the SSE
     *         (sum over hours of the sampling variance of the simulated mean),
     *         which anchors the uncertainty interval when observations are
     *         effectively noiseless.
     */
    private Eval evaluate(CalibrationRequest request, double[] observed, double beta) {
        ScenarioConfig base = request.baseScenario();
        ScenarioConfig scenario = new ScenarioConfig(
                base.population(), base.initialInfected(), beta, base.gamma(),
                base.horizonHours(), base.interventions());
        AggregateResult batch = batchRunner.run(new BatchRequest(
                scenario, request.replications(), request.masterSeed(), request.parallelism(), 0));
        int reps = batch.replications();
        int m = observed.length;
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
        double mcSseContribution = 0.0;
        for (int t = 0; t < m; t++) {
            double mean = sum[t] / reps;
            double diff = mean - observed[t];
            sse += diff * diff;
            if (reps > 1) {
                // Unbiased sample variance of a single replication, divided by
                // reps = variance of the batch mean.
                double sampleVar = (sumSq[t] - sum[t] * sum[t] / reps) / (reps - 1);
                if (sampleVar > 0.0) {
                    mcSseContribution += sampleVar / reps;
                }
            }
        }
        return new Eval(sse, mcSseContribution);
    }

    /**
     * Finds the beta on one side of the optimum where the profile SSE crosses
     * the threshold, by geometric expansion followed by bisection. Already
     * evaluated betas (grid points etc.) are reused.
     *
     * <p>Returns NaN in two distinguishable situations: the SSE never exceeds
     * the threshold before the parameter bound (genuinely unidentifiable), or
     * the evaluation budget is exhausted — callers distinguish them via
     * {@code evalCounter[0] >= maxEvals}.
     *
     * @return the crossing beta, or NaN if the bound or the budget was hit
     */
    private double findCrossing(java.util.function.DoubleFunction<Eval> evaluate,
                                int[] evalCounter, int maxEvals,
                                double bestBeta, double threshold,
                                boolean goLeft, double lower, double upper,
                                Map<Double, Eval> cache) {
        double sign = goLeft ? -1.0 : 1.0;
        double scale = Math.max(1.0, Math.abs(bestBeta));
        double inner = bestBeta;
        double outer = bestBeta;
        double outerSse = cache.get(bestBeta).sse();

        // Geometric expansion until SSE exceeds the threshold or the bound is hit.
        double step = Math.max(1e-6, CROSSING_REL_TOLERANCE) * scale;
        while (outerSse <= threshold) {
            if (evalCounter[0] >= maxEvals) {
                return Double.NaN;
            }
            double candidate = outer + sign * step;
            if ((goLeft && candidate <= lower) || (!goLeft && candidate >= upper)) {
                double atBound = evaluate.apply(goLeft ? lower : upper).sse();
                if (atBound <= threshold) {
                    return Double.NaN;
                }
                outer = goLeft ? lower : upper;
                outerSse = atBound;
                break;
            }
            outer = candidate;
            outerSse = evaluate.apply(outer).sse();
            step *= 2.0;
        }

        // Bisect [inner, outer] onto the crossing.
        double tol = CROSSING_REL_TOLERANCE * scale;
        for (int it = 0; it < 64 && Math.abs(outer - inner) > tol; it++) {
            if (evalCounter[0] >= maxEvals) {
                return Double.NaN;
            }
            double mid = 0.5 * (inner + outer);
            double midSse = evaluate.apply(mid).sse();
            if (midSse <= threshold) {
                inner = mid;
            } else {
                outer = mid;
            }
        }
        return 0.5 * (inner + outer);
    }

    /** One objective evaluation: SSE and the Monte-Carlo noise contribution. */
    private record Eval(double sse, double mcSseContribution) {
    }
}
