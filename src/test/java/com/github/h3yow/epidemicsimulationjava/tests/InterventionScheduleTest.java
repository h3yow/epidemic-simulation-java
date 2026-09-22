package com.github.h3yow.epidemicsimulationjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.epidemicsimulationjava.engine.InterventionSchedule;
import com.github.h3yow.epidemicsimulationjava.model.Intervention;
import com.github.h3yow.epidemicsimulationjava.model.InterventionType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 干预窗口 [startStep, endStep) 边界、重叠相乘与疫苗时序的确定语义。 */
class InterventionScheduleTest {

    private static final Logger log = LoggerFactory.getLogger(InterventionScheduleTest.class);

    private static Intervention reduction(String id, int start, int end, double efficacy) {
        return new Intervention(id, InterventionType.TRANSMISSION_REDUCTION, start, end, efficacy);
    }

    private static Intervention vaccination(String id, int start, double efficacy) {
        return new Intervention(id, InterventionType.VACCINATION, start, start + 1, efficacy);
    }

    @Test
    void windowIsLeftClosedRightOpen() {
        InterventionSchedule schedule = new InterventionSchedule(List.of(reduction("a", 2, 5, 0.5)), 10);
        assertEquals(1.0, schedule.transmissionMultiplier(1), 1e-12, "startStep 之前不生效");
        assertEquals(0.5, schedule.transmissionMultiplier(2), 1e-12, "startStep 当刻生效");
        assertEquals(0.5, schedule.transmissionMultiplier(4), 1e-12, "endStep-1 仍生效");
        assertEquals(1.0, schedule.transmissionMultiplier(5), 1e-12, "endStep 当刻失效");
        log.info("window [2,5) multipliers: t1={} t2={} t4={} t5={}",
                schedule.transmissionMultiplier(1), schedule.transmissionMultiplier(2),
                schedule.transmissionMultiplier(4), schedule.transmissionMultiplier(5));
    }

    @Test
    void overlappingReductionsMultiply() {
        InterventionSchedule schedule = new InterventionSchedule(List.of(
                reduction("a", 0, 10, 0.5),
                reduction("b", 5, 15, 0.2)), 20);
        assertEquals(0.5, schedule.transmissionMultiplier(4), 1e-12, "仅 a 生效");
        assertEquals(0.5 * 0.8, schedule.transmissionMultiplier(5), 1e-12, "重叠区相乘");
        assertEquals(0.8, schedule.transmissionMultiplier(10), 1e-12, "仅 b 生效");
        assertEquals(1.0, schedule.transmissionMultiplier(15), 1e-12, "全部失效");
    }

    @Test
    void vaccinationAppliesOnlyAtStartStep() {
        InterventionSchedule schedule = new InterventionSchedule(List.of(vaccination("v", 3, 0.6)), 10);
        assertTrue(schedule.vaccinationsStartingAt(2).isEmpty());
        assertEquals(1, schedule.vaccinationsStartingAt(3).size());
        assertTrue(schedule.vaccinationsStartingAt(4).isEmpty());
    }

    @Test
    void sameStepVaccinationsAreOrderedById() {
        InterventionSchedule schedule = new InterventionSchedule(List.of(
                vaccination("zeta", 3, 0.5),
                vaccination("alpha", 3, 0.5),
                vaccination("mid", 3, 0.5)), 10);
        List<Intervention> at3 = schedule.vaccinationsStartingAt(3);
        assertEquals(List.of("alpha", "mid", "zeta"),
                at3.stream().map(Intervention::id).toList(), "同刻疫苗必须按 id 升序，保证确定性");
        log.info("same-step vaccination order: {}", at3.stream().map(Intervention::id).toList());
    }

    @Test
    void outOfRangeStepsAreNeutral() {
        InterventionSchedule schedule = new InterventionSchedule(List.of(reduction("a", 0, 2, 0.9)), 5);
        assertEquals(1.0, schedule.transmissionMultiplier(-1));
        assertEquals(1.0, schedule.transmissionMultiplier(99));
        assertTrue(schedule.vaccinationsStartingAt(99).isEmpty());
    }
}
