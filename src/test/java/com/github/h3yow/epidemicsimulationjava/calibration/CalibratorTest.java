package com.github.h3yow.epidemicsimulationjava.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.epidemicsimulationjava.batch.AggregateResult;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRunner;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import com.github.h3yow.epidemicsimulationjava.simulation.ReplicationResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Calibration: distinguishable failure reasons and reproducible successful fits. */
class CalibratorTest {

    private static final Logger log = LoggerFactory.getLogger(CalibratorTest.class);

    private final BatchRunner runner = new BatchRunner();
    private final Calibrator calibrator = new Calibrator(runner);

    private static final double TRUE_BETA = 0.35;
    private static final long SEED = 777L;
    private static final int REPS = 300;

    private static ScenarioConfig scenario(double beta) {
        return new ScenarioConfig(5_000, 15, beta, 0.08, 120, List.of());
    }

    /** Synthetic observations: mean simulated curve at the true beta. */
    private double[] syntheticObservations() {
        AggregateResult batch = runner.run(
                new BatchRequest(scenario(TRUE_BETA), REPS, SEED, 4, 0));
        int horizon = scenario(TRUE_BETA).horizonHours();
        double[] mean = new double[horizon];
        for (ReplicationResult r : batch.results()) {
            long[] hourly = r.hourlyInfected();
            for (int t = 0; t < horizon; t++) {
                mean[t] += hourly[t];
            }
        }
        for (int t = 0; t < horizon; t++) {
            mean[t] /= REPS;
        }
        return mean;
    }

    private CalibrationRequest request(double[] observed, int maxEvals) {
        return new CalibrationRequest(scenario(0.2), observed, 0.1, 0.8,
                REPS, SEED, 4, maxEvals);
    }

    @Test
    void recoversTrueBetaWithUncertaintyInterval() {
        double[] observed = syntheticObservations();
        CalibrationResult result = calibrator.calibrate(request(observed, 200));
        log.info("calibration: seed={} parallelism=4 success={} beta={} CI95=[{}, {}] sse={} evals={}",
                SEED, result.success(), result.estimatedBeta(), result.betaLower95(),
                result.betaUpper95(), result.objectiveValue(), result.evaluationsUsed());
        assertTrue(result.success(), "expected success, got " + result.failureReason());
        assertNull(result.failureReason());
        assertEquals(TRUE_BETA, result.estimatedBeta(), 0.01,
                "estimate should be close to the true beta");
        assertTrue(result.betaLower95() <= result.estimatedBeta()
                && result.estimatedBeta() <= result.betaUpper95(),
                "CI must bracket the estimate");
    }

    @Test
    void calibrationIsReproducibleForSameSeed() {
        double[] observed = syntheticObservations();
        CalibrationResult a = calibrator.calibrate(request(observed, 200));
        CalibrationResult b = calibrator.calibrate(request(observed, 200));
        assertEquals(a.estimatedBeta(), b.estimatedBeta(), 0.0);
        assertEquals(a.betaLower95(), b.betaLower95(), 0.0);
        assertEquals(a.betaUpper95(), b.betaUpper95(), 0.0);
        log.info("reproducibility: seed={} beta(a)={} beta(b)={}",
                SEED, a.estimatedBeta(), b.estimatedBeta());
    }

    @Test
    void invalidBoundsAreRejectedAsParameterOutOfBounds() {
        double[] observed = syntheticObservations();
        CalibrationResult inverted = calibrator.calibrate(new CalibrationRequest(
                scenario(0.2), observed, 0.5, 0.2, REPS, SEED, 4, 100));
        assertFalse(inverted.success());
        assertEquals(CalibrationFailureReason.PARAMETER_OUT_OF_BOUNDS, inverted.failureReason());
        log.info("rejected as {}: {}", inverted.failureReason(), inverted.message());

        CalibrationResult zeroLower = calibrator.calibrate(new CalibrationRequest(
                scenario(0.2), observed, 0.0, 0.5, REPS, SEED, 4, 100));
        assertEquals(CalibrationFailureReason.PARAMETER_OUT_OF_BOUNDS, zeroLower.failureReason());
    }

    @Test
    void tooFewObservationsAreRejectedAsInsufficientData() {
        CalibrationResult result = calibrator.calibrate(
                request(new double[]{10.0, 12.0, 15.0}, 100));
        assertFalse(result.success());
        assertEquals(CalibrationFailureReason.INSUFFICIENT_DATA, result.failureReason());
        log.info("rejected as {}: {}", result.failureReason(), result.message());
    }

    @Test
    void excessiveWorkIsRejectedAsResourceLimitExceeded() {
        double[] observed = syntheticObservations();
        // 500 evaluations * 1000 replications * 120 hours = 60M simulated hours > budget.
        CalibrationResult result = calibrator.calibrate(new CalibrationRequest(
                scenario(0.2), observed, 0.1, 0.8, 1_000, SEED, 4, 500));
        assertFalse(result.success());
        assertEquals(CalibrationFailureReason.RESOURCE_LIMIT_EXCEEDED, result.failureReason());
        log.info("rejected as {}: {}", result.failureReason(), result.message());

        CalibrationResult tooManyReps = calibrator.calibrate(new CalibrationRequest(
                scenario(0.2), observed, 0.1, 0.8, 5_000, SEED, 4, 10));
        assertEquals(CalibrationFailureReason.RESOURCE_LIMIT_EXCEEDED, tooManyReps.failureReason());
    }

    @Test
    void exhaustedEvaluationBudgetIsReportedAsNonConvergence() {
        double[] observed = syntheticObservations();
        CalibrationResult result = calibrator.calibrate(request(observed, 1));
        assertFalse(result.success());
        assertEquals(CalibrationFailureReason.NON_CONVERGENCE, result.failureReason());
        log.info("rejected as {}: {}", result.failureReason(), result.message());
    }

    // ------------------------------------------------------------------
    // Noisy-observation uncertainty behaviour (profile-likelihood interval)
    // ------------------------------------------------------------------

    /** Synthetic observations: mean curve at the true beta plus deterministic noise. */
    private double[] noisyObservations(double noiseScale, long noiseSeed) {
        double[] mean = syntheticObservations();
        java.util.SplittableRandom noise = new java.util.SplittableRandom(noiseSeed);
        double[] obs = new double[mean.length];
        for (int t = 0; t < mean.length; t++) {
            obs[t] = Math.max(0.0, mean[t] + noiseScale * noise.nextGaussian());
        }
        return obs;
    }

    @Test
    void noisyObservationsYieldNonDegenerateIntervalCoveringTrueBeta() {
        // Realistic reporting noise: std = 60 infected (~2% around the peak).
        double[] observed = noisyObservations(60.0, 4242L);
        CalibrationResult result = calibrator.calibrate(request(observed, 200));
        log.info("noisy calibration: success={} beta={} CI95=[{}, {}] width={} sse={} evals={}",
                result.success(), result.estimatedBeta(), result.betaLower95(),
                result.betaUpper95(), result.betaUpper95() - result.betaLower95(),
                result.objectiveValue(), result.evaluationsUsed());
        assertTrue(result.success(), "expected success, got " + result.failureReason());
        assertEquals(TRUE_BETA, result.estimatedBeta(), 0.03,
                "estimate should remain close to true beta under noise");
        double width = result.betaUpper95() - result.betaLower95();
        assertTrue(width > 0.002,
                "interval must not degenerate to a point under noise, width=" + width);
        assertTrue(result.betaLower95() < result.estimatedBeta()
                        && result.betaUpper95() > result.estimatedBeta(),
                "interval must strictly bracket the estimate");
        assertTrue(result.betaLower95() <= TRUE_BETA && TRUE_BETA <= result.betaUpper95(),
                "95% interval should cover the true beta under calibrated noise");
    }

    @Test
    void higherNoiseProducesWiderInterval() {
        double[] lowNoise = noisyObservations(20.0, 99L);
        double[] highNoise = noisyObservations(200.0, 99L);
        CalibrationResult low = calibrator.calibrate(request(lowNoise, 200));
        CalibrationResult high = calibrator.calibrate(request(highNoise, 200));
        assertTrue(low.success() && high.success());
        double lowWidth = low.betaUpper95() - low.betaLower95();
        double highWidth = high.betaUpper95() - high.betaLower95();
        log.info("noise/width: lowNoise width={} highNoise width={}", lowWidth, highWidth);
        assertTrue(highWidth > lowWidth,
                "larger observation noise must widen the uncertainty interval");
    }

    @Test
    void noisyCalibrationIsStableAcrossRepeatsAndParallelism() {
        double[] observed = noisyObservations(60.0, 555L);
        CalibrationRequest p1 = new CalibrationRequest(scenario(0.2), observed,
                0.1, 0.8, REPS, SEED, 1, 200);
        CalibrationRequest p8 = new CalibrationRequest(scenario(0.2), observed,
                0.1, 0.8, REPS, SEED, 8, 200);
        CalibrationResult a = calibrator.calibrate(p8);
        CalibrationResult b = calibrator.calibrate(p8);
        CalibrationResult serial = calibrator.calibrate(p1);
        log.info("stability: beta(a)={} beta(b)={} beta(serial)={} CI(a)=[{},{}] CI(serial)=[{},{}]",
                a.estimatedBeta(), b.estimatedBeta(), serial.estimatedBeta(),
                a.betaLower95(), a.betaUpper95(), serial.betaLower95(), serial.betaUpper95());
        assertEquals(a.estimatedBeta(), b.estimatedBeta(), 0.0, "repeat runs must be identical");
        assertEquals(a.betaLower95(), b.betaLower95(), 0.0);
        assertEquals(a.betaUpper95(), b.betaUpper95(), 0.0);
        assertEquals(a.estimatedBeta(), serial.estimatedBeta(), 0.0,
                "parallelism must not change the estimate");
        assertEquals(a.betaLower95(), serial.betaLower95(), 0.0,
                "parallelism must not change the interval");
        assertEquals(a.betaUpper95(), serial.betaUpper95(), 0.0);
    }

    @Test
    void flatObservationsAreRejectedAsParameterUnidentifiable() {
        // A flat infectious plateau at a level the model cannot reach from
        // I(0)=15: the SSE is dominated by systematic misfit everywhere, so no
        // interior beta is preferred and the parameter is not identifiable.
        double[] flat = new double[120];
        java.util.Arrays.fill(flat, 100.0);
        CalibrationResult result = calibrator.calibrate(new CalibrationRequest(
                scenario(0.3), flat, 0.05, 0.9, REPS, SEED, 4, 200));
        log.info("flat-observation calibration: success={} reason={} message={}",
                result.success(), result.failureReason(), result.message());
        assertFalse(result.success());
        assertEquals(CalibrationFailureReason.PARAMETER_UNIDENTIFIABLE, result.failureReason());
    }

    @Test
    void tinyEvaluationBudgetDuringIntervalIsReportedAsUncertaintyNotQuantifiable() {
        double[] observed = noisyObservations(60.0, 7L);
        // 25 grid points + a few golden steps fit, but tracing the interval cannot.
        CalibrationResult result = calibrator.calibrate(request(observed, 50));
        log.info("budget-starved calibration: success={} reason={} evals={}",
                result.success(), result.failureReason(), result.evaluationsUsed());
        assertFalse(result.success());
        assertEquals(CalibrationFailureReason.UNCERTAINTY_NOT_QUANTIFIABLE, result.failureReason());
    }
}
