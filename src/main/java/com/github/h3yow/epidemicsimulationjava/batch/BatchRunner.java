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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
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
 * <p>Cancellation and timeout are both all-or-nothing: every replication is
 * interrupted, the executor is shut down, and a {@link BatchCancelledException}
 * is thrown whose {@link BatchCancelledException#isTimeout()} flag distinguishes
 * the two cases stably. Explicit interruption (including a call that already
 * arrives with its interrupt flag set) is always reported as cancellation,
 * never as timeout, even if the deadline has also elapsed.
 *
 * <p>Quiescence guarantee: by the time {@code run} returns — normally or by
 * throwing — every worker thread created for the batch has terminated. The
 * post-failure cleanup wait is uninterruptible (the caller's interrupt status
 * is restored afterwards), so a fast cancellation return can never leave
 * computation running in the background. No partial aggregate is ever returned.
 *
 * <p>Serial ({@code parallelism == 1}) and parallel paths honour the same
 * semantics; each batch owns a fresh, named pool ({@code batch-worker-<id>}),
 * so a cancelled batch leaves no state that can affect later batches.
 */
@Service
public class BatchRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchRunner.class);

    /** Monotonic batch id, embedded in worker-thread names so concurrent batches are distinguishable. */
    private static final AtomicLong BATCH_SEQ = new AtomicLong();

    /**
     * Test-instrumentation seam: invoked once at the start of every parallel
     * replication task, on the worker thread that will run that replication.
     * Production code leaves this {@code null}; cancellation tests use it to
     * park workers at a known mid-flight point. The hook must be reset to
     * {@code null} by the test that installed it.
     */
    static volatile Runnable replicationHook;

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
        // A call arriving with its interrupt flag already set is an explicit
        // cancellation — never a timeout, even if the deadline has also passed.
        if (Thread.currentThread().isInterrupted()) {
            throw new BatchCancelledException(false, "batch cancelled before it started");
        }
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
            // Explicit interruption always wins over an elapsed deadline.
            if (Thread.currentThread().isInterrupted()) {
                throw new BatchCancelledException(false, "batch cancelled before replication " + k);
            }
            checkDeadline(deadline, k);
            try {
                results[k] = SimulationEngine.run(request.scenario(), seeds[k], k);
            } catch (CancellationException e) {
                // Explicitly re-assert interruption so the failure is observable
                // as a cancellation on the executing thread as well as via the
                // exception type.
                Thread.currentThread().interrupt();
                throw new BatchCancelledException(false,
                        "batch cancelled during replication " + k);
            }
        }
        return results;
    }

    private ReplicationResult[] runParallel(BatchRequest request, long[] seeds, long deadline) {
        int reps = seeds.length;
        long batchId = BATCH_SEQ.incrementAndGet();
        // Tracks the actual worker threads so cancellation can wait until the OS
        // threads themselves have died (awaitTermination only guarantees task
        // shutdown, not Thread.isAlive()==false). Updated by worker creation,
        // which may happen off the submitting thread, so it must be concurrent.
        List<Thread> workerThreads = new CopyOnWriteArrayList<>();
        java.util.concurrent.ThreadFactory daemonFactory = runnable -> {
            Thread t = new Thread(runnable, "batch-worker-" + batchId);
            t.setDaemon(true);
            workerThreads.add(t);
            return t;
        };
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(request.parallelism(), reps), daemonFactory);
        List<Future<ReplicationResult>> futures = new ArrayList<>(reps);
        boolean clean = false;
        try {
            for (int k = 0; k < reps; k++) {
                final int index = k;
                Callable<ReplicationResult> task = () -> {
                    Runnable hook = replicationHook;
                    if (hook != null) {
                        hook.run();
                    }
                    return SimulationEngine.run(request.scenario(), seeds[index], index);
                };
                futures.add(pool.submit(task));
            }
            ReplicationResult[] results = new ReplicationResult[reps];
            for (int k = 0; k < reps; k++) {
                // Explicit interruption always wins over an elapsed deadline:
                // a caller whose thread was interrupted must get a cancellation,
                // never a timeout, regardless of when the flag arrived.
                if (Thread.currentThread().isInterrupted()) {
                    throw cancelledWhileWaiting(k);
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
                    // Future.get clears the interrupt flag; restore it so the
                    // cleanup decision below and the caller's thread both still
                    // observe an explicit cancellation.
                    Thread.currentThread().interrupt();
                    throw cancelledWhileWaiting(k);
                } catch (CancellationException e) {
                    // Our own future was cancelled out from under this loop.
                    throw new BatchCancelledException(false,
                            "replication " + k + " was cancelled");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof CancellationException) {
                        Thread.currentThread().interrupt();
                        throw new BatchCancelledException(false,
                                "replication " + k + " was cancelled");
                    }
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException("replication " + k + " failed", cause);
                }
            }
            clean = true;
            return results;
        } finally {
            // Sole cleanup path for every outcome (success, cancellation, timeout,
            // task error). Always interrupts outstanding work and blocks until
            // every worker thread is actually dead. This wait deliberately
            // ignores the caller's interrupt status: the contract is that once
            // run() returns (normally or via an exception), no background work
            // from this batch is still running. The interrupt flag is restored
            // afterwards if it was set, so cancellation is never lost.
            shutdownAndJoinWorkers(futures, pool, workerThreads, clean);
        }
    }

    /**
     * Interrupts every outstanding task, shuts the pool down and blocks until
     * both the pool has terminated and every worker thread has actually died.
     *
     * <p>The wait is <em>uninterruptible</em> on purpose: an interrupted caller
     * cancelling its batch must not return while workers are still burning CPU.
     * The method restores a pending interrupt status before returning instead of
     * throwing during the wait, so it can never mask the original failure either.
     */
    private static void shutdownAndJoinWorkers(List<Future<ReplicationResult>> futures,
                                               ExecutorService pool,
                                               List<Thread> workerThreads,
                                               boolean normalCompletion) {
        boolean wasInterrupted = Thread.interrupted();
        try {
            if (!normalCompletion) {
                for (Future<?> f : futures) {
                    f.cancel(true);
                }
                pool.shutdownNow();
            } else {
                pool.shutdown();
            }
            long giveUpAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (true) {
                long remaining = giveUpAt - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException("worker pool did not terminate");
                }
                try {
                    if (pool.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    // Keep waiting for the workers; re-assert at the end.
                    wasInterrupted = true;
                    pool.shutdownNow();
                }
            }
            for (Thread t : workerThreads) {
                if (t == Thread.currentThread()) {
                    continue;
                }
                while (t.isAlive()) {
                    long joinRemainingMillis =
                            Math.max(1L, TimeUnit.NANOSECONDS.toMillis(giveUpAt - System.nanoTime()));
                    if (System.nanoTime() >= giveUpAt) {
                        throw new IllegalStateException(
                                "worker thread " + t.getName() + " survived the batch");
                    }
                    try {
                        t.join(joinRemainingMillis);
                    } catch (InterruptedException e) {
                        // Same contract as above: finish the cleanup, never bail
                        // out with workers still alive.
                        wasInterrupted = true;
                    }
                }
            }
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static BatchCancelledException cancelledWhileWaiting(int atIndex) {
        return new BatchCancelledException(false,
                "batch cancelled while waiting for replication " + atIndex);
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
