package com.github.h3yow.epidemicsimulationjava.simulation;

import com.github.h3yow.epidemicsimulationjava.model.Intervention;
import com.github.h3yow.epidemicsimulationjava.model.InterventionType;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.random.RandomGenerator;

/**
 * Runs a single stochastic SIR replication with hourly time steps.
 *
 * <p>Model: closed population N = S + I + R. Per hour t:
 * <ul>
 *   <li>betaEff(t) = beta * product of active transmission multipliers</li>
 *   <li>gammaEff(t) = min(gamma + sum of active treatment increments, 1)</li>
 *   <li>newInfections ~ Binomial(S, 1 - exp(-betaEff * I / N))</li>
 *   <li>newRecoveries ~ Binomial(I, 1 - exp(-gammaEff))</li>
 * </ul>
 *
 * <p>Determinism: all randomness comes from one {@link SplittableRandom} created
 * from the replication seed; the draw sequence depends only on (scenario, seed),
 * never on scheduling or parallelism.
 */
public final class SimulationEngine {

    private SimulationEngine() {
    }

    /**
     * Runs one replication.
     *
     * @param config           validated scenario
     * @param seed             replication-private seed
     * @param replicationIndex index recorded in the result
     * @return the replication outcome
     * @throws CancellationException if the current thread was interrupted (cooperative cancellation)
     */
    public static ReplicationResult run(ScenarioConfig config, long seed, int replicationIndex) {
        RandomGenerator rng = new SplittableRandom(seed);
        long n = config.population();
        long s = n - config.initialInfected();
        long i = config.initialInfected();
        long r = 0;

        int horizon = config.horizonHours();
        long[] hourly = new long[horizon];
        long peak = i;

        for (int t = 0; t < horizon; t++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("replication " + replicationIndex + " interrupted at hour " + t);
            }
            double betaEff = effectiveBeta(config, t);
            double gammaEff = effectiveGamma(config, t);

            double pInfect = -Math.expm1(-betaEff * i / n);
            double pRecover = -Math.expm1(-gammaEff);

            long newInfections = binomial(rng, s, pInfect);
            long newRecoveries = binomial(rng, i, pRecover);

            s -= newInfections;
            i += newInfections - newRecoveries;
            r += newRecoveries;

            hourly[t] = i;
            if (i > peak) {
                peak = i;
            }
        }
        return new ReplicationResult(replicationIndex, seed, hourly, peak, r);
    }

    static double effectiveBeta(ScenarioConfig config, int hour) {
        double multiplier = 1.0;
        for (Intervention iv : config.interventions()) {
            switch (iv.type()) {
                case SOCIAL_DISTANCING, MASKING -> {
                    if (iv.activeAt(hour)) {
                        multiplier *= iv.effect();
                    }
                }
                case TREATMENT -> {
                    // does not affect transmission
                }
            }
        }
        return config.beta() * multiplier;
    }

    static double effectiveGamma(ScenarioConfig config, int hour) {
        double gamma = config.gamma();
        for (Intervention iv : config.interventions()) {
            if (iv.type() == InterventionType.TREATMENT && iv.activeAt(hour)) {
                gamma += iv.effect();
            }
        }
        return Math.min(gamma, 1.0);
    }

    /**
     * Deterministic binomial sampler. Exact inverse transform by coin flipping for
     * small n*min(p,1-p); normal approximation otherwise. The branch taken depends
     * only on (n, p), so the draw sequence is stable for a given scenario.
     */
    static long binomial(RandomGenerator rng, long n, double p) {
        if (n <= 0 || p <= 0.0) {
            return 0;
        }
        if (p >= 1.0) {
            return n;
        }
        // Use symmetry to keep the mean of the flipped variate small.
        boolean flipped = p > 0.5;
        double q = flipped ? 1.0 - p : p;
        double mean = n * q;
        long result;
        if (mean <= 20.0) {
            if (n <= 100) {
                // Exact: coin flipping, cheap for small n.
                long count = 0;
                for (long k = 0; k < n; k++) {
                    if (rng.nextDouble() < q) {
                        count++;
                    }
                }
                result = count;
            } else {
                // Poisson approximation (Knuth) for small mean with large n.
                double limit = Math.exp(-mean);
                long count = -1;
                double prod = 1.0;
                do {
                    prod *= rng.nextDouble();
                    count++;
                } while (prod > limit);
                result = Math.min(count, n);
            }
        } else {
            double sd = Math.sqrt(n * q * (1.0 - q));
            long sample = Math.round(mean + sd * rng.nextGaussian());
            result = Math.max(0, Math.min(n, sample));
        }
        return flipped ? n - result : result;
    }
}
