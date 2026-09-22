package com.github.h3yow.epidemicsimulationjava.model;

/**
 * 干预措施类型。
 * TRANSMISSION_REDUCTION：在生效窗口内对传播率乘上 (1 - efficacy)。
 * VACCINATION：在窗口起始时刻，将易感者中比例 efficacy 的人一次性免疫。
 */
public enum InterventionType {
    TRANSMISSION_REDUCTION,
    VACCINATION
}
