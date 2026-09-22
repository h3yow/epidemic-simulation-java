package com.github.h3yow.epidemicsimulationjava.model;

import java.util.List;

/**
 * 批量重复实验的聚合结论。区间为跨重复实验的 25%/75% 分位数区间。
 *
 * <p>结论只是 (scenario, baseSeed, replications) 的纯函数：不包含并行度等
 * 执行方式元数据，因此同一种子在不同并行度下的结果对象完全相等。
 */
public record BatchResult(
        long baseSeed,
        int replications,
        List<TracePoint> trace,
        double meanPeakInfected,
        double peakLower,
        double peakUpper
) {
}
