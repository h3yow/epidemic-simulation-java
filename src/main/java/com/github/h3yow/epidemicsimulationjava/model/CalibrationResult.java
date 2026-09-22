package com.github.h3yow.epidemicsimulationjava.model;

import java.util.List;

/** 校准结论：点估计与加权不确定度区间。 */
public record CalibrationResult(
        double betaEstimate,
        double betaLowBound,
        double betaHighBound,
        double bestRmse,
        List<GridPoint> grid,
        int replicationsPerPoint
) {
}
