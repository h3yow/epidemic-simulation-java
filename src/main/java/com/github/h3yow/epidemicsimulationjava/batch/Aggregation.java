package com.github.h3yow.epidemicsimulationjava.batch;

import com.github.h3yow.epidemicsimulationjava.model.BatchResult;
import com.github.h3yow.epidemicsimulationjava.model.ReplicationResult;
import com.github.h3yow.epidemicsimulationjava.model.TracePoint;
import java.util.ArrayList;
import java.util.List;

/**
 * 聚合工具：把多次重复实验汇总为均值 + 分位数区间。
 *
 * <p>区间定义为 25%/75% 分位数（线性插值，等价于线性插值经验分位数）。
 * 聚合只按 replicateIndex 取输入，内部对每个时刻的值拷贝排序，
 * 因此结论与提交/完成顺序、并行度、任务划分完全无关。
 */
final class Aggregation {

    private Aggregation() {
    }

    static BatchResult aggregate(List<ReplicationResult> replications, int steps, long baseSeed) {
        int reps = replications.size();
        double[] means = new double[steps];
        double[] lowers = new double[steps];
        double[] uppers = new double[steps];
        double[] buffer = new double[reps];

        for (int t = 0; t < steps; t++) {
            double sum = 0.0;
            for (int k = 0; k < reps; k++) {
                ReplicationResult rr = replications.get(k);
                int infected = rr.snapshots().get(t).exposed() + rr.snapshots().get(t).infectious();
                buffer[k] = infected;
                sum += infected;
            }
            java.util.Arrays.sort(buffer);
            means[t] = sum / reps;
            lowers[t] = quantileSorted(buffer, 0.25);
            uppers[t] = quantileSorted(buffer, 0.75);
        }

        List<TracePoint> trace = new ArrayList<>(steps);
        for (int t = 0; t < steps; t++) {
            trace.add(new TracePoint(t, means[t], lowers[t], uppers[t]));
        }

        double peakSum = 0.0;
        for (int k = 0; k < reps; k++) {
            buffer[k] = replications.get(k).peakInfected();
            peakSum += buffer[k];
        }
        java.util.Arrays.sort(buffer);

        return new BatchResult(
                baseSeed,
                reps,
                trace,
                peakSum / reps,
                quantileSorted(buffer, 0.25),
                quantileSorted(buffer, 0.75));
    }

    /** 已排序数组上的线性插值分位数。 */
    private static double quantileSorted(double[] sorted, double q) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = lo + 1;
        double frac = pos - lo;
        if (hi >= sorted.length) {
            return sorted[sorted.length - 1];
        }
        return sorted[lo] * (1.0 - frac) + sorted[hi] * frac;
    }
}
