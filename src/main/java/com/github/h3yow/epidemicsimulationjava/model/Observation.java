package com.github.h3yow.epidemicsimulationjava.model;

/** 观测数据点：step 时刻观测到的感染者（含暴露+感染）人数。 */
public record Observation(
        int step,
        int observedInfected
) {
}
