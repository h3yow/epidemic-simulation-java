package com.github.h3yow.epidemicsimulationjava.batch;

import com.github.h3yow.epidemicsimulationjava.config.SimulationProperties;
import com.github.h3yow.epidemicsimulationjava.engine.ScenarioValidator;
import com.github.h3yow.epidemicsimulationjava.engine.SeedStreams;
import com.github.h3yow.epidemicsimulationjava.engine.SimulationEngine;
import com.github.h3yow.epidemicsimulationjava.exception.SimulationException;
import com.github.h3yow.epidemicsimulationjava.model.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.model.BatchResult;
import com.github.h3yow.epidemicsimulationjava.model.FailureReason;
import com.github.h3yow.epidemicsimulationjava.model.ReplicationResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 批量重复实验执行器：串行/并行统一入口。
 *
 * <p>可复现性约定：第 i 次重复的种子 = f(baseSeed, i)，与并行度无关；聚合按
 * replicateIndex 升序进行，因此同一 baseSeed 在任意 parallelism 下结论逐位一致。
 *
 * <p>取消/超时语义：一旦超时或线程被中断，立即取消全部未完成任务、关闭执行器，
 * 抛出带 {@link FailureReason#TIMEOUT}/{@link FailureReason#CANCELLED} 的异常，
 * 不返回任何部分结果，也不留下后台任务。
 */
@Component
public class BatchRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchRunner.class);

    private final SimulationEngine engine;
    private final ScenarioValidator validator;
    private final SimulationProperties properties;

    public BatchRunner(SimulationEngine engine, ScenarioValidator validator,
                       SimulationProperties properties) {
        this.engine = engine;
        this.validator = validator;
        this.properties = properties;
    }

    public BatchResult run(BatchRequest request) {
        validateRequest(request);
        validator.validate(request.scenario());

        long deadlineNanos = System.nanoTime() + request.timeout().toNanos();
        List<ReplicationResult> replications = request.parallelism() == 1
                ? runSerial(request, deadlineNanos)
                : runParallel(request, deadlineNanos);

        BatchResult result = Aggregation.aggregate(
                replications, request.scenario().steps(), request.baseSeed());
        log.info("batch done: baseSeed={} parallelism={} replications={} meanPeakInfected={} peakInterval=[{},{}]",
                result.baseSeed(), request.parallelism(), result.replications(),
                result.meanPeakInfected(), result.peakLower(), result.peakUpper());
        return result;
    }

    private void validateRequest(BatchRequest request) {
        if (request == null) {
            throw new SimulationException(FailureReason.INVALID_SCENARIO, "BatchRequest 不能为空");
        }
        if (request.replications() < 1) {
            throw new SimulationException(FailureReason.INVALID_SCENARIO,
                    "replications 必须 >= 1，实际: " + request.replications());
        }
        if (request.replications() > properties.maxReplications()) {
            throw new SimulationException(FailureReason.RESOURCE_LIMIT_EXCEEDED,
                    "replications=" + request.replications() + " 超过上限 " + properties.maxReplications());
        }
        if (request.parallelism() < 1) {
            throw new SimulationException(FailureReason.INVALID_SCENARIO,
                    "parallelism 必须 >= 1，实际: " + request.parallelism());
        }
        if (request.parallelism() > properties.maxParallelism()) {
            throw new SimulationException(FailureReason.RESOURCE_LIMIT_EXCEEDED,
                    "parallelism=" + request.parallelism() + " 超过上限 " + properties.maxParallelism());
        }
        if (request.timeout() == null || request.timeout().isNegative() || request.timeout().isZero()) {
            throw new SimulationException(FailureReason.INVALID_SCENARIO, "timeout 必须为正时长");
        }
        if (request.timeout().compareTo(properties.maxTimeout()) > 0) {
            throw new SimulationException(FailureReason.RESOURCE_LIMIT_EXCEEDED,
                    "timeout=" + request.timeout() + " 超过上限 " + properties.maxTimeout());
        }
    }

    private List<ReplicationResult> runSerial(BatchRequest request, long deadlineNanos) {
        List<ReplicationResult> results = new ArrayList<>(request.replications());
        for (int i = 0; i < request.replications(); i++) {
            checkAlive(deadlineNanos);
            long seed = SeedStreams.replicateSeed(request.baseSeed(), i);
            results.add(engine.runReplication(request.scenario(), seed, i));
        }
        return results;
    }

    private List<ReplicationResult> runParallel(BatchRequest request, long deadlineNanos) {
        ExecutorService pool = Executors.newFixedThreadPool(request.parallelism());
        try {
            List<Future<ReplicationResult>> futures = new ArrayList<>(request.replications());
            for (int i = 0; i < request.replications(); i++) {
                final int idx = i;
                final long seed = SeedStreams.replicateSeed(request.baseSeed(), idx);
                Callable<ReplicationResult> task =
                        () -> engine.runReplication(request.scenario(), seed, idx);
                futures.add(pool.submit(task));
            }
            List<ReplicationResult> results = new ArrayList<>(request.replications());
            for (Future<ReplicationResult> future : futures) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    cancelAll(futures);
                    throw timeoutException(request);
                }
                try {
                    results.add(future.get(remaining, TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    cancelAll(futures);
                    throw timeoutException(request);
                } catch (CancellationException e) {
                    cancelAll(futures);
                    throw new SimulationException(FailureReason.CANCELLED, "批量任务被取消", e);
                } catch (ExecutionException e) {
                    cancelAll(futures);
                    Throwable cause = e.getCause();
                    if (cause instanceof SimulationException se) {
                        throw se;
                    }
                    throw new SimulationException(FailureReason.INVALID_SCENARIO,
                            "重复实验执行失败: " + cause, cause);
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SimulationException(FailureReason.CANCELLED, "批量任务被中断取消", e);
        } finally {
            // 无论成功/失败/取消，执行器都必须关闭，不留下后台任务
            pool.shutdownNow();
        }
    }

    private void checkAlive(long deadlineNanos) {
        if (Thread.currentThread().isInterrupted()) {
            throw new SimulationException(FailureReason.CANCELLED, "批量任务被中断取消");
        }
        if (System.nanoTime() > deadlineNanos) {
            throw new SimulationException(FailureReason.TIMEOUT, "批量任务超过时限，已整体取消");
        }
    }

    private static void cancelAll(List<? extends Future<?>> futures) {
        for (Future<?> f : futures) {
            f.cancel(true);
        }
    }

    private static SimulationException timeoutException(BatchRequest request) {
        return new SimulationException(FailureReason.TIMEOUT,
                "批量任务超过时限 " + request.timeout() + "，已整体取消，未计入任何部分结果");
    }
}
