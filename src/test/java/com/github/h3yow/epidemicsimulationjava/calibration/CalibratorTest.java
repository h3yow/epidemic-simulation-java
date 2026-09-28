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
    // Noisy observations: interval must reflect the noise, never collapse
    // ------------------------------------------------------------------

    /** Mean curve plus i.i.d. Gaussian observation noise (clipped at 0). */
    private double[] noisyObservations(double noiseSigma, long noiseSeed) {
        double[] mean = syntheticObservations();
        java.util.SplittableRandom rng = new java.util.SplittableRandom(noiseSeed);
        double[] noisy = new double[mean.length];
        for (int t = 0; t < mean.length; t++) {
            noisy[t] = Math.max(0.0, mean[t] + noiseSigma * rng.nextGaussian());
        }
        return noisy;
    }

    @Test
    void noisyObservationsGiveNonDegenerateIntervalReflectingNoise() {
        double sigma = 30.0;
        double[] noisy = noisyObservations(sigma, 4242L);
        CalibrationResult result = calibrator.calibrate(request(noisy, 500));
        log.info("noisy calibration: success={} beta={} CI95=[{}, {}] width={} noiseSigma={} sse={}",
                result.success(), result.estimatedBeta(), result.betaLower95(),
                result.betaUpper95(),
                result.success() ? result.betaUpper95() - result.betaLower95() : Double.NaN,
                result.noiseSigma(), result.objectiveValue());
        assertTrue(result.success(), "expected success, got " + result.failureReason());
        assertEquals(TRUE_BETA, result.estimatedBeta(), 0.02,
                "estimate should still be close to the true beta under noise");
        // Interval brackets the estimate and is meaningfully wider than a point.
        assertTrue(result.betaLower95() < result.estimatedBeta()
                        && result.estimatedBeta() < result.betaUpper95(),
                "CI must strictly bracket the estimate");
        double width = result.betaUpper95() - result.betaLower95();
        assertTrue(width > 1e-4, "noisy-data CI must not collapse to a point, got width " + width);
        // The reported noise level must reflect the injected observation noise.
        assertTrue(result.noiseSigma() >= sigma * 0.5,
                "noiseSigma should reflect the observation noise, got " + result.noiseSigma());
    }

    @Test
    void noisyCalibrationIsStableAcrossRepeatsAndParallelism() {
        double[] noisy = noisyObservations(30.0, 4242L);
        CalibrationRequest serial = new CalibrationRequest(
                scenario(0.2), noisy, 0.1, 0.8, REPS, SEED, 1, 500);
        CalibrationRequest parallel = new CalibrationRequest(
                scenario(0.2), noisy, 0.1, 0.8, REPS, SEED, 8, 500);
        CalibrationResult a = calibrator.calibrate(serial);
        CalibrationResult b = calibrator.calibrate(parallel);
        CalibrationResult c = calibrator.calibrate(serial);
        assertTrue(a.success() && b.success() && c.success());
        assertEquals(a.estimatedBeta(), b.estimatedBeta(), 0.0);
        assertEquals(a.betaLower95(), b.betaLower95(), 0.0);
        assertEquals(a.betaUpper95(), b.betaUpper95(), 0.0);
        assertEquals(a.estimatedBeta(), c.estimatedBeta(), 0.0);
        assertEquals(a.betaLower95(), c.betaLower95(), 0.0);
        assertEquals(a.betaUpper95(), c.betaUpper95(), 0.0);
        log.info("noisy stability: serial CI=[{},{}] parallel8 CI=[{},{}] repeat CI=[{},{}]",
                a.betaLower95(), a.betaUpper95(), b.betaLower95(), b.betaUpper95(),
                c.betaLower95(), c.betaUpper95());
    }

    @Test
    void largerObservationNoiseProducesNoNarrowerInterval() {
        double[] lowNoise = noisyObservations(5.0, 4242L);
        double[] highNoise = noisyObservations(60.0, 4242L);
        CalibrationResult low = calibrator.calibrate(request(lowNoise, 500));
        CalibrationResult high = calibrator.calibrate(request(highNoise, 500));
        assertTrue(low.success() && high.success());
        double lowWidth = low.betaUpper95() - low.betaLower95();
        double highWidth = high.betaUpper95() - high.betaLower95();
        log.info("noise/width: sigma=5 width={} sigma=60 width={}", lowWidth, highWidth);
        assertTrue(highWidth >= lowWidth,
                "interval under high noise must be no narrower than under low noise");
        assertTrue(high.noiseSigma() > low.noiseSigma());
    }

    @Test
    void flatObservationsAreReportedAsParameterUnidentifiable() {
        double[] flat = new double[120];
        java.util.Arrays.fill(flat, 15.0);
        CalibrationResult result = calibrator.calibrate(
                new CalibrationRequest(scenario(0.2), flat, 0.1, 0.8, 60, SEED, 4, 500));
        assertFalse(result.success());
        assertEquals(CalibrationFailureReason.PARAMETER_UNIDENTIFIABLE, result.failureReason());
        log.info("rejected as {}: {}", result.failureReason(), result.message());
    }

    @Test
    void capacityRejectionCarriesDimensionAsDetailReason() {
        double[] observed = syntheticObservations();
        CalibrationResult tooMany = calibrator.calibrate(new CalibrationRequest(
                scenario(0.2), observed, 0.1, 0.8, 5_000, SEED, 4, 10));
        assertFalse(tooMany.success());
        assertEquals(CalibrationFailureReason.RESOURCE_LIMIT_EXCEEDED, tooMany.failureReason());
        assertEquals("REPLICATIONS", tooMany.detailReason());
        log.info("capacity rejection detail: {}", tooMany.detailReason());
    }
}
