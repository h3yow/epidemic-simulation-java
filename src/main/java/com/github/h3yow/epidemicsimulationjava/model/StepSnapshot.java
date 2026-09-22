package com.github.h3yow.epidemicsimulationjava.model;

/** 单个时间步推进结束后的人群快照与本步增量。 */
public record StepSnapshot(
        int step,
        int susceptible,
        int exposed,
        int infectious,
        int recovered,
        int newExposures,
        int newInfections
) {
}
