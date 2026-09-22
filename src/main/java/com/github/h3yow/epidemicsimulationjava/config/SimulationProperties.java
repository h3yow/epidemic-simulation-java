package com.github.h3yow.epidemicsimulationjava.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 资源上限等本地配置。 */
@ConfigurationProperties(prefix = "epidemic")
public record SimulationProperties(
        int maxPopulation,
        int maxSteps,
        int maxReplications,
        int maxParallelism,
        int maxGridPoints,
        int minObservations,
        Duration maxTimeout
) {
    public SimulationProperties {
        maxPopulation = maxPopulation <= 0 ? 1_000_000 : maxPopulation;
        maxSteps = maxSteps <= 0 ? 100_000 : maxSteps;
        maxReplications = maxReplications <= 0 ? 100_000 : maxReplications;
        maxParallelism = maxParallelism <= 0 ? 32 : maxParallelism;
        maxGridPoints = maxGridPoints <= 0 ? 10_000 : maxGridPoints;
        minObservations = minObservations <= 0 ? 2 : minObservations;
        maxTimeout = maxTimeout == null ? Duration.ofMinutes(10) : maxTimeout;
    }
}
