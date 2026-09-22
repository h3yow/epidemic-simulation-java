package com.github.h3yow.epidemicsimulationjava.model;

import java.time.Duration;

/**
 * 批量重复实验请求。
 * parallelism=1 表示串行，>1 表示并行；结果与 parallelism 无关。
 */
public record BatchRequest(
        EpidemicScenario scenario,
        int replications,
        long baseSeed,
        int parallelism,
        Duration timeout
) {
}
