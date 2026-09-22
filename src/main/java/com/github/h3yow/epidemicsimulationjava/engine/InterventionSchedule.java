package com.github.h3yow.epidemicsimulationjava.engine;

import com.github.h3yow.epidemicsimulationjava.model.Intervention;
import com.github.h3yow.epidemicsimulationjava.model.InterventionType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 干预时刻表：承载窗口边界、重叠与重复的确定语义。
 *
 * <p>窗口统一为左闭右开 {@code [startStep, endStep)}：startStep 时刻起生效，
 * endStep 时刻起失效（最后一次生效是 endStep-1）。推演从 step 0 的初始状态
 * 推进到 step steps-1 的快照，共 steps 个快照。
 *
 * <p>重叠语义（确定且无歧义）：
 * <ul>
 *   <li>{@code TRANSMISSION_REDUCTION}：同一时刻多个传播率干预重叠时，乘子
 *       {@code Π(1-efficacy)} 相乘（独立折减的合取解释）。</li>
 *   <li>{@code VACCINATION}：在每个干预自己的 {@code startStep} 时刻一次性施加；
 *       同一时刻多个疫苗干预按 id 升序依次施加，顺序确定，折减均针对当时剩余易感者。</li>
 * </ul>
 */
public final class InterventionSchedule {

    private final double[] transmissionMultiplier;
    private final List<List<Intervention>> vaccinationsByStep;

    public InterventionSchedule(List<Intervention> interventions, int steps) {
        this.transmissionMultiplier = new double[steps];
        for (int t = 0; t < steps; t++) {
            transmissionMultiplier[t] = 1.0;
        }
        List<Intervention> vaccinations = new ArrayList<>();
        if (interventions != null) {
            for (Intervention iv : interventions) {
                if (iv.type() == InterventionType.TRANSMISSION_REDUCTION) {
                    // [startStep, endStep) 左闭右开
                    for (int t = iv.startStep(); t < iv.endStep(); t++) {
                        transmissionMultiplier[t] *= (1.0 - iv.efficacy());
                    }
                } else if (iv.type() == InterventionType.VACCINATION) {
                    vaccinations.add(iv);
                }
            }
        }
        vaccinations.sort(Comparator.comparing(Intervention::startStep)
                .thenComparing(Intervention::id));
        this.vaccinationsByStep = new ArrayList<>(steps);
        for (int t = 0; t < steps; t++) {
            vaccinationsByStep.add(new ArrayList<>());
        }
        for (Intervention iv : vaccinations) {
            vaccinationsByStep.get(iv.startStep()).add(iv);
        }
    }

    /** step 时刻推进时使用的传播率乘子；无干预为 1.0。 */
    public double transmissionMultiplier(int step) {
        if (step < 0 || step >= transmissionMultiplier.length) {
            return 1.0;
        }
        return transmissionMultiplier[step];
    }

    /** 恰好在 step 时刻起始生效的疫苗干预（已按 id 升序）。 */
    public List<Intervention> vaccinationsStartingAt(int step) {
        if (step < 0 || step >= vaccinationsByStep.size()) {
            return List.of();
        }
        return vaccinationsByStep.get(step);
    }
}
