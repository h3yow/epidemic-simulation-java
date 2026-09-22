package com.github.h3yow.epidemicsimulationjava.model;

/**
 * 干预措施。窗口语义为 [startStep, endStep)：startStep 时刻生效，endStep 时刻失效。
 * @param id 唯一标识，不可重复
 * @param type 干预类型
 * @param startStep 生效起始时刻（含）
 * @param endStep 失效时刻（不含）
 * @param efficacy 强度，取值 [0,1]
 */
public record Intervention(
        String id,
        InterventionType type,
        int startStep,
        int endStep,
        double efficacy
) {
}
