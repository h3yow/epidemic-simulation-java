package com.github.h3yow.epidemicsimulationjava.batch;

import com.github.h3yow.epidemicsimulationjava.capacity.CapacityGuard;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioValidator;
import com.github.h3yow.epidemicsimulationjava.simulation.ReplicationResult;
import com.github.h3yow.epidemicsimulationjava.simulation.SimulationEngine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Executes batches of stochastic replications, serially or in parallel.
 *
 * <p>Reproducibility contract: per-replication seeds are derived from the master
 * seed <em>before</em> any task is scheduled, and results are aggregated in
 * replication-index order. The aggregate output therefore depends only on
 * (scenario, replications, masterSeed) — never on parallelism or task partitioning.
 *
 * <p>Cancellation/timeout is all-or-nothing: on either, every task is interrupted,
 * the executor is shut down and awaited, and a {@link BatchCancelledException} is
 * thrown. No partial aggregate is ever returned and no task keeps running.
 */
@Service
public class BatchRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchRunner.class);

    private final CapacityGuard capacityGuard;

    /** Used by Spring: enforces the externally configured capacity limits. */
    @Autowired
    public BatchRunner(CapacityGuard capacityGuard) {
        this.capacityGuard = capacityGuard;
    }

    /** Convenience constructor for tests with default capacity limits. */
    public BatchRunner() {
        this(new CapacityGuard());
    }

    /**
     * Runs a batch to completion or fails as a whole.
     *
     * @param request batch request
     * @return aggregate result (never partial)
     * @throws BatchCancelledException     if cancelled or timed out
     * @throws IllegalArgumentException    if the request parameters are invalid
     * @throws com.github.h3yow.epidemicsimulationjava.capacity.CapacityExceededException
     *         if a configured capacity bound is exceeded (rejected before any task is scheduled)
     */
    public AggregateResult run(BatchRequest request) {
        ScenarioValidator.validate(request.scenario());
        if (request.replications() < 1) {
            throw new IllegalArgumentException("replications must be >= 1, got " + request.replications());
        }
        if (request.parallelism() < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1, got " + request.parallelism());
        }
        // Enforce external capacity limits strictly before seeds are derived or
        // any worker task is scheduled.
        capacityGuard.checkBatch(request);

        int reps = request.replications();
        long[] seeds = deriveSeeds(request.masterSeed(), reps);
        long deadline = request.timeoutMillis() > 0
                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis())
                : Long.MAX_VALUE;

        log.info("batch start: replications={} parallelism={} masterSeed={} timeoutMillis={} "
                        + "population={} horizonHours={}",
                reps, request.parallelism(), request.masterSeed(), request.timeoutMillis(),
                request.scenario().population(), request.scenario().horizonHours());

        ReplicationResult[] results = request.parallelism() == 1
                ? runSerial(request, seeds, deadline)
                : runParallel(request, seeds, deadline);

        AggregateResult aggregate = aggregate(results);
        log.info("batch done: replications={} parallelism={} masterSeed={} "
                        + "meanPeakInfected={} peakP2.5={} peakP97.5={} meanTotalRecovered={}",
                reps, request.parallelism(), request.masterSeed(),
                aggregate.meanPeakInfected(), aggregate.peakInfectedP2_5(),
                aggregate.peakInfectedP97_5(), aggregate.meanTotalRecovered());
        return aggregate;
    }

    /** Derives per-replication seeds serially, in index order, before any scheduling. */
    static long[] deriveSeeds(long masterSeed, int replications) {
        SplittableRandom master = new SplittableRandom(masterSeed);
        long[] seeds = new long[replications];
        for (int k = 0; k < replications; k++) {
            seeds[k] = master.nextLong();
        }
        return seeds;
    }

    private ReplicationResult[] runSerial(BatchRequest request, long[] seeds, long deadline) {
        ReplicationResult[] results = new ReplicationResult[seeds.length];
        for (int k = 0; k < seeds.length; k++) {
            checkDeadline(deadline, k);
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new BatchCancelledException(false, "batch cancelled before replication " + k);
            }
            try {
                results[k] = SimulationEngine.run(request.scenario(), seeds[k], k);
            } catch (CancellationException e) {
                Thread.currentThread().interrupt();
                throw new BatchCancelledException(false,
                        "batch cancelled during replication " + k);
            }
        }
        return results;
    }

    private ReplicationResult[] runParallel(BatchRequest request, long[] seeds, long deadline) {
        int reps = seeds.length;
        // Tracks the actual worker threads so cancellation can wait until the OS
        // threads themselves have died (awaitTermination only guarantees task
        // shutdown, not Thread.isAlive()==false).
        java.util.List<Thread> workerThreads = new ArrayList<>();
        java.util.concurrent.ThreadFactory daemonFactory = runnable -> {
            Thread t = new Thread(runnable, "batch-worker");
            t.setDaemon(true);
            workerThreads.add(t);
            return t;
        };
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(request.parallelism(), reps), daemonFactory);
        List<Future<ReplicationResult>> futures = new ArrayList<>(reps);
        try {
            for (int k = 0; k < reps; k++) {
                final int index = k;
                Callable<ReplicationResult> task =
                        () -> SimulationEngine.run(request.scenario(), seeds[index], index);
                futures.add(pool.submit(task));
            }
            ReplicationResult[] results = new ReplicationResult[reps];
            for (int k = 0; k < reps; k++) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw timeoutException(k);
                }
                try {
                    results[k] = futures.get(k).get(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    throw timeoutException(k);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BatchCancelledException(false, "batch cancelled while waiting for replication " + k);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof CancellationException) {
                        throw new BatchCancelledException(false, "replication " + k + " was cancelled");
                    }
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException("replication " + k + " failed", cause);
                }
            }
            return results;
        } catch (BatchCancelledException e) {
            cancelAll(futures, pool, workerThreads);
            throw e;
        } catch (RuntimeException e) {
            cancelAll(futures, pool, workerThreads);
            throw e;
        } finally {
            // Normal completion path: shut down and also wait for the worker
            // threads themselves to die, so no "batch-worker" thread outlives
            // the batch even after a successful return.
            pool.shutdown();
            awaitWorkerDeath(pool, workerThreads);
        }
    }

    /**
     * Interrupts every task, shuts the pool down and waits until both the pool
     * has terminated and every worker thread has actually died. No background
     * work survives this call.
     */
    private static void cancelAll(List<Future<ReplicationResult>> futures, ExecutorService pool,
                                  java.util.List<Thread> workerThreads) {
        for (Future<?> f : futures) {
            f.cancel(true);
        }
        pool.shutdownNow();
        awaitWorkerDeath(pool, workerThreads);
    }

    /** Awaits pool termination and then joins every worker thread until it is dead. */
    private static void awaitWorkerDeath(ExecutorService pool, java.util.List<Thread> workerThreads) {
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("worker pool did not terminate");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Thread t : workerThreads) {
            if (t == Thread.currentThread()) {
                continue;
            }
            try {
                t.join(30_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BatchCancelledException(false, "interrupted while waiting for worker to die");
            }
            if (t.isAlive()) {
                throw new IllegalStateException("worker thread " + t.getName() + " survived the batch");
            }
        }
    }

    private static void checkDeadline(long deadline, int nextIndex) {
        if (System.nanoTime() >= deadline) {
            throw timeoutException(nextIndex);
        }
    }

    private static BatchCancelledException timeoutException(int atIndex) {
        return new BatchCancelledException(true, "batch timed out before replication " + atIndex);
    }

    /**
     * Aggregates in replication-index order. Percentiles use linear interpolation
     * between closest ranks (same convention as R type-7 quantiles).
     */
    static AggregateResult aggregate(ReplicationResult[] results) {
        int n = results.length;
        double[] peaks = new double[n];
        double totalRecoveredSum = 0;
        for (int k = 0; k < n; k++) {
            peaks[k] = results[k].peakInfected();
            totalRecoveredSum += results[k].totalRecovered();
        }
        double meanPeak = Arrays.stream(peaks).average().orElse(0);
        double[] sorted = peaks.clone();
        Arrays.sort(sorted);
        return new AggregateResult(
                n,
                meanPeak,
                percentile(sorted, 0.025),
                percentile(sorted, 0.975),
                totalRecoveredSum / n,
                List.of(results));
    }

    static double percentile(double[] sorted, double p) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double rank = p * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = Math.min(lo + 1, sorted.length - 1);
        double frac = rank - lo;
        return sorted[lo] * (1.0 - frac) + sorted[hi] * frac;
    }
}
