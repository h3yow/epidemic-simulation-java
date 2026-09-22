package com.github.h3yow.epidemicsimulationjava.model;

/** 校准网格上的单个评估点。 */
public record GridPoint(
        double beta,
        double rmse,
        double weight
) {
}
