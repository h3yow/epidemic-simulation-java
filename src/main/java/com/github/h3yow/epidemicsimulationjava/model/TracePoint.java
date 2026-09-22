package com.github.h3yow.epidemicsimulationjava.model;

/** 某一时刻的聚合结果：均值与分位数区间。 */
public record TracePoint(
        int step,
        double mean,
        double lower,
        double upper
) {
}
