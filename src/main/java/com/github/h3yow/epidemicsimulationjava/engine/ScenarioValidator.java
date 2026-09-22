package com.github.h3yow.epidemicsimulationjava.engine;

import com.github.h3yow.epidemicsimulationjava.config.SimulationProperties;
import com.github.h3yow.epidemicsimulationjava.exception.SimulationException;
import com.github.h3yow.epidemicsimulationjava.model.EpidemicScenario;
import com.github.h3yow.epidemicsimulationjava.model.FailureReason;
import com.github.h3yow.epidemicsimulationjava.model.Intervention;
import com.github.h3yow.epidemicsimulationjava.model.InterventionType;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 情景与干预配置校验：非法或自相矛盾的配置一律拒绝，绝不静默接受。
 *
 * <p>单条干预字段不合法归因为 {@link FailureReason#INVALID_INTERVENTION}；
 * 干预之间重复（id 冲突）归因为 {@link FailureReason#CONFLICTING_INTERVENTIONS}；
 * 情景级参数不合法归因为 {@link FailureReason#INVALID_SCENARIO}。
 */
@Component
public class ScenarioValidator {

    private final SimulationProperties properties;

    public ScenarioValidator(SimulationProperties properties) {
        this.properties = properties;
    }

    public void validate(EpidemicScenario scenario) {
        if (scenario == null) {
            throw new SimulationException(FailureReason.INVALID_SCENARIO, "scenario 不能为空");
        }
        validateScenarioFields(scenario);
        validateInterventions(scenario);
    }

    private void validateScenarioFields(EpidemicScenario s) {
        if (s.population() < 1) {
            throw invalidScenario("population 必须 >= 1，实际: " + s.population());
        }
        if (s.population() > properties.maxPopulation()) {
            throw resourceLimit("population=" + s.population() + " 超过上限 " + properties.maxPopulation());
        }
        if (s.initialExposed() < 0 || s.initialInfectious() < 0) {
            throw invalidScenario("初始感染人数不能为负");
        }
        if (s.initialExposed() + s.initialInfectious() > s.population()) {
            throw invalidScenario("初始暴露+感染人数超过总人口: "
                    + (s.initialExposed() + s.initialInfectious()) + " > " + s.population());
        }
        requireUnitRate(s.betaPerStep(), "betaPerStep");
        requireUnitRate(s.sigmaPerStep(), "sigmaPerStep");
        requireUnitRate(s.gammaPerStep(), "gammaPerStep");
        if (s.steps() < 1) {
            throw invalidScenario("steps 必须 >= 1，实际: " + s.steps());
        }
        if (s.steps() > properties.maxSteps()) {
            throw resourceLimit("steps=" + s.steps() + " 超过上限 " + properties.maxSteps());
        }
        if (s.interventions() == null) {
            throw new SimulationException(FailureReason.INVALID_SCENARIO, "interventions 不能为 null（可用空列表）");
        }
    }

    private void requireUnitRate(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw invalidScenario(field + " 必须是 [0,1] 内的有限值，实际: " + value);
        }
    }

    private void validateInterventions(EpidemicScenario s) {
        Set<String> seenIds = new HashSet<>();
        for (Intervention iv : s.interventions()) {
            validateOne(iv, s.steps());
            if (!seenIds.add(iv.id())) {
                throw new SimulationException(FailureReason.CONFLICTING_INTERVENTIONS,
                        "干预 id 重复: " + iv.id() + "；重复配置的语义不明确，必须显式合并后重提");
            }
        }
    }

    private void validateOne(Intervention iv, int steps) {
        if (iv == null) {
            throw new SimulationException(FailureReason.INVALID_INTERVENTION, "干预项不能为 null");
        }
        if (iv.id() == null || iv.id().isBlank()) {
            throw new SimulationException(FailureReason.INVALID_INTERVENTION, "干预 id 不能为空");
        }
        if (iv.type() == null) {
            throw new SimulationException(FailureReason.INVALID_INTERVENTION,
                    "干预[" + iv.id() + "] type 不能为空");
        }
        if (!Double.isFinite(iv.efficacy()) || iv.efficacy() < 0.0 || iv.efficacy() > 1.0) {
            throw new SimulationException(FailureReason.INVALID_INTERVENTION,
                    "干预[" + iv.id() + "] efficacy 必须是 [0,1] 内的有限值，实际: " + iv.efficacy());
        }
        if (iv.efficacy() == 0.0 && iv.type() == InterventionType.TRANSMISSION_REDUCTION) {
            throw new SimulationException(FailureReason.INVALID_INTERVENTION,
                    "干预[" + iv.id() + "] efficacy=0 是自相矛盾的空配置，请删除或改为正值");
        }
        if (iv.startStep() < 0) {
            throw new SimulationException(FailureReason.INVALID_INTERVENTION,
                    "干预[" + iv.id() + "] startStep 不能为负: " + iv.startStep());
        }
        if (iv.endStep() <= iv.startStep()) {
            throw new SimulationException(FailureReason.INVALID_INTERVENTION,
                    "干预[" + iv.id() + "] 窗口非法: endStep(" + iv.endStep()
                            + ") 必须大于 startStep(" + iv.startStep() + ")");
        }
        if (iv.startStep() >= steps || iv.endStep() > steps) {
            throw new SimulationException(FailureReason.INVALID_INTERVENTION,
                    "干预[" + iv.id() + "] 窗口 [" + iv.startStep() + "," + iv.endStep()
                            + ") 完全或部分超出推演时长 steps=" + steps
                            + "；窗口必须在推演域内至少生效一个时间步");
        }
    }

    private static SimulationException invalidScenario(String msg) {
        return new SimulationException(FailureReason.INVALID_SCENARIO, msg);
    }

    private static SimulationException resourceLimit(String msg) {
        return new SimulationException(FailureReason.RESOURCE_LIMIT_EXCEEDED, msg);
    }
}
