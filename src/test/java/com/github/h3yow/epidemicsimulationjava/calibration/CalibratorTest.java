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
}
