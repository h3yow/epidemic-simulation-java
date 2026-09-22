package com.github.h3yow.epidemicsimulationjava.model;

/** SEIR 人群状态。时间步单位为小时（step = hour）。 */
public enum HealthState {
    SUSCEPTIBLE,
    EXPOSED,
    INFECTIOUS,
    RECOVERED
}
