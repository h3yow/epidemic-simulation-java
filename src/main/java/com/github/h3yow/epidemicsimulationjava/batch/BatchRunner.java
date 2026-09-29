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
 * the executor is shut down, and {@code run} blocks until <em>every worker thread
 * of the batch has actually terminated</em> before throwing
 * {@link BatchCancelledException}. At the instant the exception propagates to the
 * caller no background computation is running and no {@code batch-worker} thread
 * is alive; a new batch is never affected by a previously cancelled one.
 *
 * <p>Failure semantics are stable and the same on the serial and parallel paths:
 * <ul>
 *   <li>an interrupt (including one already present when {@code run} is entered)
 *       is always reported as cancellation: {@code isTimeout() == false}, and the
 *       caller thread keeps its interrupted status;</li>
 *   <li>an elapsed deadline with no interrupt is always reported as timeout:
 *       {@code isTimeout() == true}, and the caller thread's interrupt status is
 *       left untouched (a timeout never poisons the next call);</li>
 *   <li>if both occur at the same moment, cancellation wins;</li>
 *   <li>no partial aggregate is ever returned.</li>
 * </ul>
 */
@Service
public class BatchRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchRunner.class);

    /** Bounded wait for the pool/worker threads to die during cleanup. */
    private static final long AWAIT_SECONDS = 30L;

    private final CapacityGuard capacityGuard;
    private final ReplicationExecutor replicationExecutor;
    private final java.util.concurrent.atomic.AtomicLong batchSequence =
            new java.util.concurrent.atomic.AtomicLong();

    /** Used by Spring: enforces the externally configured capacity limits. */
    @Autowired
    public BatchRunner(CapacityGuard capacityGuard) {
        this(capacityGuard, SimulationEngine::run);
    }

    /** Convenience constructor for tests with default capacity limits. */
    public BatchRunner() {
        this(new CapacityGuard());
    }

    /** Package-private seam: substitute the per-replication computation (tests only). */
    BatchRunner(CapacityGuard capacityGuard, ReplicationExecutor replicationExecutor) {
        this.capacityGuard = capacityGuard;
        this.replicationExecutor = replicationExecutor;
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

        // A caller that already carries an interrupt (e.g. an HTTP request whose
        // connection was closed before we started) must fail fast, be classified
        // as cancellation — never timeout — and schedule no background work.
        if (Thread.currentThread().isInterrupted()) {
            throw new BatchCancelledException(false, "batch cancelled before it started");
        }

        int reps = request.replications();
        long[] seeds = deriveSeeds(request.masterSeed(), reps);
        long deadline = request.timeoutMillis() > 0
                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis())
                : Long.MAX_VALUE;

        log.info("batch start: replications={} parallelism={} masterSeed={} timeoutMillis={} "
                        + "population={} horizonHours={}",
                reps, request.parallelism(), request.masterSeed(), request.timeoutMillis(),
                request.scenario().population(), request.scenario().horizonHours());

        long batchId = batchSequence.incrementAndGet();
        ReplicationResult[] results = request.parallelism() == 1
                ? runSerial(request, seeds, deadline)
                : runParallel(request, seeds, deadline, batchId);

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
            // Cancellation takes precedence over timeout: if both are observed at
            // the same moment the explicit/interrupt signal wins, matching the
            // parallel path.
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new BatchCancelledException(false, "batch cancelled before replication " + k);
            }
            checkDeadline(deadline, k);
            try {
                results[k] = replicationExecutor.run(request.scenario(), seeds[k], k);
            } catch (CancellationException e) {
                Thread.currentThread().interrupt();
                throw new BatchCancelledException(false,
                        "batch cancelled during replication " + k);
            }
        }
        return results;
    }

    private ReplicationResult[] runParallel(BatchRequest request, long[] seeds, long deadline,
                                            long batchId) {
        int reps = seeds.length;
        // Tracks the actual worker threads so cancellation can wait until the OS
        // threads themselves have died (awaitTermination only guarantees task
        // shutdown, not Thread.isAlive()==false).
        java.util.List<Thread> workerThreads = new ArrayList<>();
        java.util.concurrent.ThreadFactory daemonFactory = runnable -> {
            Thread t = new Thread(runnable, "batch-worker-" + batchId);
            t.setDaemon(true);
            workerThreads.add(t);
            return t;
        };
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(request.parallelism(), reps), daemonFactory);
        List<Future<ReplicationResult>> futures = new ArrayList<>(reps);
        boolean aborted = false;
        try {
            for (int k = 0; k < reps; k++) {
                final int index = k;
                Callable<ReplicationResult> task =
                        () -> replicationExecutor.run(request.scenario(), seeds[index], index);
                futures.add(pool.submit(task));
            }
            ReplicationResult[] results = new ReplicationResult[reps];
            for (int k = 0; k < reps; k++) {
                // Cancellation takes precedence: if the caller is interrupted at
                // the same moment the deadline expires, report cancellation.
                if (Thread.currentThread().isInterrupted()) {
                    throw new BatchCancelledException(false,
                            "batch cancelled while waiting for replication " + k);
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw timeoutException(k);
                }
                try {
                    results[k] = futures.get(k).get(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    throw timeoutException(k);
                } catch (InterruptedException e) {
                    // Do NOT re-assert the interrupt yet: the cleanup below must
                    // run with a clear status, otherwise awaiting worker death
                    // would abort instantly and leak threads.
                    throw new BatchCancelledException(false,
                            "batch cancelled while waiting for replication " + k);
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
            aborted = true;
            // Interrupt every in-flight task, shut the pool down and block until
            // every worker thread is actually dead. This wait is deliberately
            // immune to the caller's own interrupt status, so the guarantee
            // "cancellation returned => nothing is still running" holds even when
            // the calling thread is the one that was interrupted.
            boolean callerWasInterrupted = Thread.interrupted();
            shutdownAndAwait(futures, pool, workerThreads, true);
            // Restore the caller's interrupt status only when this was an actual
            // cancellation that the caller signalled via interrupt. A timeout is
            // not an interrupt: the caller thread must leave run() with a clear
            // status, so subsequent calls on that thread are not poisoned.
            if (!e.isTimeout() && callerWasInterrupted) {
                Thread.currentThread().interrupt();
            }
            throw e;
        } catch (RuntimeException e) {
            aborted = true;
            shutdownAndAwait(futures, pool, workerThreads, true);
            throw e;
        } finally {
            if (!aborted) {
                // Normal completion: shut down gracefully and still wait for the
                // worker threads themselves to die, so no batch-worker outlives
                // a successfully returned batch.
                shutdownAndAwait(futures, pool, workerThreads, false);
            }
        }
    }

    /**
     * Cancels (on abort) or shuts down (on success) all tasks, shuts the pool
     * down and then waits until every worker thread has actually died.
     *
     * <p>The wait is bounded and deliberately does not react to the calling
     * thread's interrupt status: an interrupted caller is exactly the
     * cancellation case, and returning before the workers are dead would leak
     * them. Any pre-existing interrupt is cleared for the duration of the wait
     * and restored afterwards.
     */
    private static void shutdownAndAwait(List<Future<ReplicationResult>> futures,
                                        ExecutorService pool,
                                        java.util.List<Thread> workerThreads,
                                        boolean interruptTasks) {
        boolean callerWasInterrupted = Thread.interrupted();
        try {
            if (interruptTasks) {
                for (Future<?> f : futures) {
                    f.cancel(true);
                }
                pool.shutdownNow();
            } else {
                pool.shutdown();
            }

            long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
            // First wait for the pool to finish tearing tasks down. Retry while
            // the deadline holds even if an interrupt slips in (it must not,
            // because we cleared the status, but stay defensive).
            while (true) {
                try {
                    if (pool.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                        break;
                    }
                    throw new IllegalStateException("worker pool did not terminate");
                } catch (InterruptedException e) {
                    if (System.nanoTime() >= waitDeadline) {
                        throw new IllegalStateException(
                                "interrupted while waiting for worker pool to terminate");
                    }
                    // swallow and keep waiting; interrupt status restored below
                }
            }

            // awaitTermination only guarantees the pool's tasks have stopped;
            // join each tracked worker thread so that at return time
            // Thread.isAlive() is already false for all of them.
            for (Thread t : workerThreads) {
                if (t == Thread.currentThread()) {
                    continue;
                }
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(
                        Math.max(1L, waitDeadline - System.nanoTime()));
                try {
                    t.join(remainingMillis);
                } catch (InterruptedException e) {
                    if (System.nanoTime() >= waitDeadline) {
                        throw new IllegalStateException(
                                "interrupted while waiting for worker to die");
                    }
                    // swallow and keep waiting
                }
                if (t.isAlive()) {
                    throw new IllegalStateException(
                            "worker thread " + t.getName() + " survived the batch");
                }
            }
        } finally {
            if (callerWasInterrupted) {
                Thread.currentThread().interrupt();
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
