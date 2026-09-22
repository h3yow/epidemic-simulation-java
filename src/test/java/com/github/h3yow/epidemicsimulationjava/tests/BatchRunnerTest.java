package com.github.h3yow.epidemicsimulationjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.epidemicsimulationjava.batch.BatchRunner;
import com.github.h3yow.epidemicsimulationjava.config.SimulationProperties;
import com.github.h3yow.epidemicsimulationjava.engine.ScenarioValidator;
import com.github.h3yow.epidemicsimulationjava.engine.SeirSimulationEngine;
import com.github.h3yow.epidemicsimulationjava.exception.SimulationException;
import com.github.h3yow.epidemicsimulationjava.model.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.model.BatchResult;
import com.github.h3yow.epidemicsimulationjava.model.EpidemicScenario;
import com.github.h3yow.epidemicsimulationjava.model.FailureReason;
import com.github.h3yow.epidemicsimulationjava.model.ReplicationResult;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 批量执行器：可复现性（种子 × 并行度）、取消/超时整体生效、资源上限拒绝。
 */
class BatchRunnerTest {

    private static final Logger log = LoggerFactory.getLogger(BatchRunnerTest.class);

    private static final long BASE_SEED = 20260922L;

    private final SimulationProperties props = ScenarioValidatorTest.defaultProps();
    private final SeirSimulationEngine engine = new SeirSimulationEngine();
    private final BatchRunner runner =
            new BatchRunner(engine, new ScenarioValidator(props), props);

    private static EpidemicScenario scenario() {
        return new TestScenarios.Builder().population(2_000).steps(120).build();
    }

    private static BatchRequest request(int replications, int parallelism, Duration timeout) {
        return new BatchRequest(scenario(), replications, BASE_SEED, parallelism, timeout);
    }

    @Test
    void sameSeedSerialAndParallelProduceIdenticalResults() {
        BatchResult serial = runner.run(request(8, 1, Duration.ofMinutes(2)));
        BatchResult parallel = runner.run(request(8, 4, Duration.ofMinutes(2)));
        log.info("reproducibility check: baseSeed={} serial(parallelism=1) meanPeak={} interval=[{},{}]",
                BASE_SEED, serial.meanPeakInfected(), serial.peakLower(), serial.peakUpper());
        log.info("reproducibility check: baseSeed={} parallel(parallelism=4) meanPeak={} interval=[{},{}]",
                BASE_SEED, parallel.meanPeakInfected(), parallel.peakLower(), parallel.peakUpper());
        assertEquals(serial, parallel,
                "同一种子在串行与并行下的聚合结论必须逐位一致");
        assertEquals(serial.trace(), parallel.trace(), "逐时刻均值与区间也必须一致");
    }

    @Test
    void sameSeedDifferentParallelismLevelsAllIdentical() {
        BatchResult p1 = runner.run(request(6, 1, Duration.ofMinutes(2)));
        BatchResult p2 = runner.run(request(6, 2, Duration.ofMinutes(2)));
        BatchResult p3 = runner.run(request(6, 3, Duration.ofMinutes(2)));
        assertEquals(p1, p2);
        assertEquals(p1, p3);
        log.info("parallelism invariance: seed={} p1==p2==p3 meanPeak={}",
                BASE_SEED, p1.meanPeakInfected());
    }

    @Test
    void differentSeedsGiveDifferentResults() {
        BatchResult a = runner.run(request(4, 2, Duration.ofMinutes(2)));
        BatchResult b = runner.run(new BatchRequest(scenario(), 4, BASE_SEED + 1, 2, Duration.ofMinutes(2)));
        assertNotEquals(a.meanPeakInfected(), b.meanPeakInfected(),
                "不同种子应产生不同轨迹（ sanity check ）");
    }

    @Test
    void engineIsDeterministicPerSeed() {
        ReplicationResult r1 = engine.runReplication(scenario(), 42L, 0);
        ReplicationResult r2 = engine.runReplication(scenario(), 42L, 0);
        assertEquals(r1, r2, "同一种子的单次重复实验必须完全可复现");
    }

    @Test
    void timeoutAbortsWholeBatchWithoutPartialResult() {
        int poolThreadsBefore = countPoolThreads();
        SimulationException ex = assertThrows(SimulationException.class,
                () -> runner.run(request(50, 4, Duration.ofNanos(1))));
        assertEquals(FailureReason.TIMEOUT, ex.getReason());
        log.info("timeout rejected: reason={} message={}", ex.getReason(), ex.getMessage());
        assertTrue(awaitPoolThreadsDrain(poolThreadsBefore),
                "超时后不得留下仍在运行的后台任务");
    }

    @Test
    void serialTimeoutAlsoAborts() {
        SimulationException ex = assertThrows(SimulationException.class,
                () -> runner.run(request(50, 1, Duration.ofNanos(1))));
        assertEquals(FailureReason.TIMEOUT, ex.getReason());
    }

    @Test
    void interruptCancelsBatch() {
        Thread.currentThread().interrupt();
        try {
            SimulationException ex = assertThrows(SimulationException.class,
                    () -> runner.run(request(10, 1, Duration.ofMinutes(1))));
            assertEquals(FailureReason.CANCELLED, ex.getReason());
            log.info("cancelled as expected: reason={} message={}", ex.getReason(), ex.getMessage());
        } finally {
            Thread.interrupted(); // 清除中断标记，避免影响后续测试
        }
    }

    @Test
    void rejectsExcessiveReplications() {
        SimulationException ex = assertThrows(SimulationException.class,
                () -> runner.run(request(props.maxReplications() + 1, 1, Duration.ofMinutes(1))));
        assertEquals(FailureReason.RESOURCE_LIMIT_EXCEEDED, ex.getReason());
        log.info("resource limit: reason={} message={}", ex.getReason(), ex.getMessage());
    }

    @Test
    void rejectsExcessiveParallelism() {
        SimulationException ex = assertThrows(SimulationException.class,
                () -> runner.run(request(2, props.maxParallelism() + 1, Duration.ofMinutes(1))));
        assertEquals(FailureReason.RESOURCE_LIMIT_EXCEEDED, ex.getReason());
    }

    @Test
    void rejectsExcessiveTimeout() {
        SimulationException ex = assertThrows(SimulationException.class,
                () -> runner.run(request(2, 1, props.maxTimeout().plusSeconds(1))));
        assertEquals(FailureReason.RESOURCE_LIMIT_EXCEEDED, ex.getReason());
    }

    @Test
    void rejectsNonPositiveParallelismAndReplications() {
        SimulationException ex1 = assertThrows(SimulationException.class,
                () -> runner.run(request(2, 0, Duration.ofMinutes(1))));
        assertEquals(FailureReason.INVALID_SCENARIO, ex1.getReason());
        SimulationException ex2 = assertThrows(SimulationException.class,
                () -> runner.run(request(0, 1, Duration.ofMinutes(1))));
        assertEquals(FailureReason.INVALID_SCENARIO, ex2.getReason());
    }

    private static int countPoolThreads() {
        int count = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("pool-")) {
                count++;
            }
        }
        return count;
    }

    private static boolean awaitPoolThreadsDrain(int baseline) {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            if (countPoolThreads() <= baseline) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
