package com.github.h3yow.epidemicsimulationjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.epidemicsimulationjava.batch.BatchRunner;
import com.github.h3yow.epidemicsimulationjava.calibration.CalibrationService;
import com.github.h3yow.epidemicsimulationjava.config.SimulationProperties;
import com.github.h3yow.epidemicsimulationjava.engine.ScenarioValidator;
import com.github.h3yow.epidemicsimulationjava.engine.SeirSimulationEngine;
import com.github.h3yow.epidemicsimulationjava.exception.SimulationException;
import com.github.h3yow.epidemicsimulationjava.model.CalibrationRequest;
import com.github.h3yow.epidemicsimulationjava.model.CalibrationResult;
import com.github.h3yow.epidemicsimulationjava.model.EpidemicScenario;
import com.github.h3yow.epidemicsimulationjava.model.FailureReason;
import com.github.h3yow.epidemicsimulationjava.model.Observation;
import com.github.h3yow.epidemicsimulationjava.model.ReplicationResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 参数校准：成功路径输出不确定度区间；各类失败原因可区分。 */
class CalibrationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(CalibrationServiceTest.class);

    private final SimulationProperties props = ScenarioValidatorTest.defaultProps();
    private final SeirSimulationEngine engine = new SeirSimulationEngine();
    private final ScenarioValidator validator = new ScenarioValidator(props);
    private final CalibrationService service =
            new CalibrationService(new BatchRunner(engine, validator, props), validator, props);

    private static EpidemicScenario scenario() {
        return new TestScenarios.Builder().population(2_000).steps(60).build();
    }

    /** 用固定种子生成一条"真实"轨迹，取若干时刻的感染数作为观测。 */
    private List<Observation> syntheticObservations(long truthSeed) {
        ReplicationResult truth = engine.runReplication(scenario(), truthSeed, 0);
        List<Observation> obs = new ArrayList<>();
        for (int step = 10; step <= 50; step += 10) {
            var snap = truth.snapshots().get(step);
            obs.add(new Observation(step, snap.exposed() + snap.infectious()));
        }
        log.info("synthetic observations from truthSeed={}: {}", truthSeed, obs);
        return obs;
    }

    private CalibrationRequest request(List<Observation> obs, double tolerance) {
        return new CalibrationRequest(scenario(), obs, 0.01, 0.10, 10, 8, tolerance,
                Duration.ofMinutes(5));
    }

    @Test
    void calibratesWithUncertaintyInterval() {
        CalibrationResult result = service.calibrate(request(syntheticObservations(777L), 1_000.0));
        log.info("calibration result: betaEstimate={} interval=[{},{}] bestRmse={} gridPoints={}",
                result.betaEstimate(), result.betaLowBound(), result.betaHighBound(),
                result.bestRmse(), result.grid().size());
        assertTrue(result.betaEstimate() >= 0.01 && result.betaEstimate() <= 0.10,
                "点估计必须落在搜索区间内");
        assertTrue(result.betaLowBound() <= result.betaEstimate()
                        && result.betaEstimate() <= result.betaHighBound(),
                "不确定度区间必须包含点估计");
        assertTrue(result.bestRmse() < 1_000.0);
        assertEquals(10, result.grid().size());
        assertTrue(result.grid().stream().allMatch(g -> g.weight() > 0.0),
                "所有网格点必须有正权重");
    }

    @Test
    void rejectsInsufficientData() {
        SimulationException empty = assertThrows(SimulationException.class,
                () -> service.calibrate(request(List.of(), 100.0)));
        assertEquals(FailureReason.INSUFFICIENT_DATA, empty.getReason());

        List<Observation> tooFew = List.of(new Observation(10, 5), new Observation(20, 8));
        SimulationException few = assertThrows(SimulationException.class,
                () -> service.calibrate(request(tooFew, 100.0)));
        assertEquals(FailureReason.INSUFFICIENT_DATA, few.getReason());
        log.info("insufficient data rejected: reason={} message={}", few.getReason(), few.getMessage());
    }

    @Test
    void rejectsInvalidSearchRange() {
        CalibrationRequest inverted = new CalibrationRequest(scenario(), syntheticObservations(1L),
                0.10, 0.01, 10, 4, 100.0, Duration.ofMinutes(1));
        SimulationException ex = assertThrows(SimulationException.class,
                () -> service.calibrate(inverted));
        assertEquals(FailureReason.INVALID_CALIBRATION_REQUEST, ex.getReason());
        log.info("invalid range rejected: reason={} message={}", ex.getReason(), ex.getMessage());
    }

    @Test
    void rejectsObservationOutsideHorizon() {
        List<Observation> bad = List.of(
                new Observation(10, 5), new Observation(20, 8), new Observation(999, 3));
        SimulationException ex = assertThrows(SimulationException.class,
                () -> service.calibrate(request(bad, 100.0)));
        assertEquals(FailureReason.INVALID_CALIBRATION_REQUEST, ex.getReason());
    }

    @Test
    void reportsNoConvergenceWhenToleranceUnreachable() {
        SimulationException ex = assertThrows(SimulationException.class,
                () -> service.calibrate(request(syntheticObservations(777L), 1e-9)));
        assertEquals(FailureReason.NO_CONVERGENCE, ex.getReason());
        log.info("no convergence reported: reason={} message={}", ex.getReason(), ex.getMessage());
    }

    @Test
    void rejectsExcessiveGridAndWorkload() {
        SimulationProperties tight = new SimulationProperties(
                1_000_000, 100_000, 20, 32, 5, 3, Duration.ofMinutes(10));
        CalibrationService tightService = new CalibrationService(
                new BatchRunner(engine, validator, tight), validator, tight);
        List<Observation> obs = syntheticObservations(3L);

        SimulationException grid = assertThrows(SimulationException.class,
                () -> tightService.calibrate(new CalibrationRequest(scenario(), obs,
                        0.01, 0.1, 10, 2, 100.0, Duration.ofMinutes(1))));
        assertEquals(FailureReason.RESOURCE_LIMIT_EXCEEDED, grid.getReason());

        SimulationException workload = assertThrows(SimulationException.class,
                () -> tightService.calibrate(new CalibrationRequest(scenario(), obs,
                        0.01, 0.1, 4, 10, 100.0, Duration.ofMinutes(1))));
        assertEquals(FailureReason.RESOURCE_LIMIT_EXCEEDED, workload.getReason());
        log.info("resource limits rejected: grid={} workload={}", grid.getMessage(), workload.getMessage());
    }

    @Test
    void timeoutPropagatesAsDistinctReason() {
        CalibrationRequest tinyTimeout = new CalibrationRequest(scenario(), syntheticObservations(5L),
                0.01, 0.1, 4, 4, 1_000.0, Duration.ofNanos(1));
        SimulationException ex = assertThrows(SimulationException.class,
                () -> service.calibrate(tinyTimeout));
        assertEquals(FailureReason.TIMEOUT, ex.getReason());
        log.info("calibration timeout: reason={} message={}", ex.getReason(), ex.getMessage());
    }
}
