package com.github.h3yow.epidemicsimulationjava.model;

import java.util.List;

/** 单次随机重复实验的完整轨迹与汇总指标。 */
public record ReplicationResult(
        int replicateIndex,
        long seed,
        List<StepSnapshot> snapshots,
        int peakInfected,
        int totalInfected
) {
}
