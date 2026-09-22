package com.github.h3yow.epidemicsimulationjava.engine;

import com.github.h3yow.epidemicsimulationjava.model.EpidemicScenario;
import com.github.h3yow.epidemicsimulationjava.model.Intervention;
import com.github.h3yow.epidemicsimulationjava.model.ReplicationResult;
import com.github.h3yow.epidemicsimulationjava.model.StepSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * 离散时间随机 SEIR 引擎。
 *
 * <p>模型假设（每小时一个时间步，所有转移独立二项抽样）：
 * <pre>
 *   newExposures_t   ~ Binomial(S, betaEff * I / N)
 *   newInfections_t  ~ Binomial(E, sigma)   （E -> I）
 *   newRecovered_t   ~ Binomial(I, gamma)   （I -> R）
 * </pre>
 * 疫苗在 step t 起始时刻先施加（易感者按 efficacy 比例一次性移除），随后再做本步传播。
 * 全部随机数仅来自由 (baseSeed, replicateIndex) 派生的固定算法 RNG，因此轨迹可复现，
 * 且与执行方式（串行/并行）无关。
 */
@Component
public class SeirSimulationEngine implements SimulationEngine {

    @Override
    public ReplicationResult runReplication(EpidemicScenario sc, long seed, int replicateIndex) {
        RandomGenerator rng = SeedStreams.newRng(seed);

        int n = sc.population();
        int s = n - sc.initialExposed() - sc.initialInfectious();
        int e = sc.initialExposed();
        int i = sc.initialInfectious();
        int r = 0;

        InterventionSchedule schedule = new InterventionSchedule(sc.interventions(), sc.steps());

        List<StepSnapshot> snapshots = new ArrayList<>(sc.steps());
        int peakInfected = e + i;
        int totalInfected = e + i;

        for (int t = 0; t < sc.steps(); t++) {
            // 1) 本时刻起始的疫苗干预（同刻按 id 升序，确定顺序）
            for (Intervention vax : schedule.vaccinationsStartingAt(t)) {
                int vaccinated = sampleBinomial(rng, s, vax.efficacy());
                s -= vaccinated;
                r += vaccinated;
            }

            // 2) 传播折减乘子
            double betaEff = sc.betaPerStep() * schedule.transmissionMultiplier(t);
            double infectionProb = betaEff * (double) i / (double) n;
            if (infectionProb < 0.0) {
                infectionProb = 0.0;
            } else if (infectionProb > 1.0) {
                infectionProb = 1.0;
            }

            // 3) 三条独立转移链
            int newExposures = sampleBinomial(rng, s, infectionProb);
            int newInfections = sampleBinomial(rng, e, sc.sigmaPerStep());
            int newRecovered = sampleBinomial(rng, i, sc.gammaPerStep());

            s -= newExposures;
            e += newExposures - newInfections;
            i += newInfections - newRecovered;
            r += newRecovered;

            totalInfected += newExposures;
            peakInfected = Math.max(peakInfected, e + i);

            snapshots.add(new StepSnapshot(t, s, e, i, r, newExposures, newInfections));
        }

        return new ReplicationResult(replicateIndex, seed, snapshots, peakInfected, totalInfected);
    }

    /**
     * 二项抽样 Binomial(n, p)。按固定顺序逐个伯努利试验实现：结果只取决于 RNG
     * 随机流与 n，不依赖任何并行或内部洗牌。
     */
    private static int sampleBinomial(RandomGenerator rng, int n, double p) {
        if (n <= 0 || p <= 0.0) {
            return 0;
        }
        if (p >= 1.0) {
            return n;
        }
        int count = 0;
        for (int k = 0; k < n; k++) {
            if (rng.nextDouble() < p) {
                count++;
            }
        }
        return count;
    }
}
