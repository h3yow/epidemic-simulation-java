package com.github.h3yow.epidemicsimulationjava.tests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.h3yow.epidemicsimulationjava.config.SimulationProperties;
import com.github.h3yow.epidemicsimulationjava.engine.ScenarioValidator;
import com.github.h3yow.epidemicsimulationjava.exception.SimulationException;
import com.github.h3yow.epidemicsimulationjava.model.EpidemicScenario;
import com.github.h3yow.epidemicsimulationjava.model.FailureReason;
import com.github.h3yow.epidemicsimulationjava.model.Intervention;
import com.github.h3yow.epidemicsimulationjava.model.InterventionType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 非法与自相矛盾配置必须被明确拒绝，且失败原因可区分。 */
class ScenarioValidatorTest {

    private static final Logger log = LoggerFactory.getLogger(ScenarioValidatorTest.class);

    private final ScenarioValidator validator = new ScenarioValidator(defaultProps());

    static SimulationProperties defaultProps() {
        return new SimulationProperties(1_000_000, 100_000, 100_000, 32, 10_000, 3,
                java.time.Duration.ofMinutes(10));
    }

    static TestScenarios.Builder scenario() {
        return new TestScenarios.Builder();
    }

    private SimulationException expectInvalid(Runnable r, FailureReason reason) {
        SimulationException ex = assertThrows(SimulationException.class, r::run);
        log.info("rejected as expected: reason={} message={}", ex.getReason(), ex.getMessage());
        assertEquals(reason, ex.getReason(), "失败原因分类不符合预期");
        return ex;
    }

    @Test
    void validScenarioPasses() {
        EpidemicScenario s = scenario().build();
        assertDoesNotThrow(() -> validator.validate(s));
    }

    @Test
    void rejectsNonPositivePopulation() {
        expectInvalid(() -> validator.validate(scenario().population(0).build()),
                FailureReason.INVALID_SCENARIO);
    }

    @Test
    void rejectsInitialInfectedExceedingPopulation() {
        expectInvalid(() -> validator.validate(
                        scenario().population(100).initialExposed(60).initialInfectious(50).build()),
                FailureReason.INVALID_SCENARIO);
    }

    @Test
    void rejectsRateOutOfUnitRange() {
        expectInvalid(() -> validator.validate(scenario().beta(1.5).build()),
                FailureReason.INVALID_SCENARIO);
        expectInvalid(() -> validator.validate(scenario().sigma(-0.1).build()),
                FailureReason.INVALID_SCENARIO);
        expectInvalid(() -> validator.validate(scenario().gamma(Double.NaN).build()),
                FailureReason.INVALID_SCENARIO);
    }

    @Test
    void rejectsNonPositiveSteps() {
        expectInvalid(() -> validator.validate(scenario().steps(0).build()),
                FailureReason.INVALID_SCENARIO);
    }

    @Test
    void rejectsPopulationOverResourceLimit() {
        expectInvalid(() -> validator.validate(scenario().population(2_000_000).build()),
                FailureReason.RESOURCE_LIMIT_EXCEEDED);
    }

    @Test
    void rejectsBlankInterventionId() {
        Intervention bad = new Intervention(" ", InterventionType.TRANSMISSION_REDUCTION, 0, 10, 0.5);
        expectInvalid(() -> validator.validate(scenario().interventions(List.of(bad)).build()),
                FailureReason.INVALID_INTERVENTION);
    }

    @Test
    void rejectsEfficacyOutOfRange() {
        Intervention bad = new Intervention("a", InterventionType.TRANSMISSION_REDUCTION, 0, 10, 1.5);
        expectInvalid(() -> validator.validate(scenario().interventions(List.of(bad)).build()),
                FailureReason.INVALID_INTERVENTION);
    }

    @Test
    void rejectsZeroEfficacyTransmissionReductionAsContradictory() {
        Intervention bad = new Intervention("a", InterventionType.TRANSMISSION_REDUCTION, 0, 10, 0.0);
        expectInvalid(() -> validator.validate(scenario().interventions(List.of(bad)).build()),
                FailureReason.INVALID_INTERVENTION);
    }

    @Test
    void rejectsEmptyOrInvertedWindow() {
        Intervention empty = new Intervention("a", InterventionType.TRANSMISSION_REDUCTION, 5, 5, 0.5);
        expectInvalid(() -> validator.validate(scenario().interventions(List.of(empty)).build()),
                FailureReason.INVALID_INTERVENTION);
        Intervention inverted = new Intervention("b", InterventionType.VACCINATION, 8, 3, 0.5);
        expectInvalid(() -> validator.validate(scenario().interventions(List.of(inverted)).build()),
                FailureReason.INVALID_INTERVENTION);
    }

    @Test
    void rejectsWindowOutsideHorizon() {
        Intervention beyond = new Intervention("a", InterventionType.TRANSMISSION_REDUCTION, 0, 200, 0.5);
        expectInvalid(() -> validator.validate(scenario().steps(100).interventions(List.of(beyond)).build()),
                FailureReason.INVALID_INTERVENTION);
        Intervention startsTooLate = new Intervention("b", InterventionType.VACCINATION, 100, 101, 0.5);
        expectInvalid(() -> validator.validate(scenario().steps(100).interventions(List.of(startsTooLate)).build()),
                FailureReason.INVALID_INTERVENTION);
    }

    @Test
    void rejectsDuplicateInterventionIdsAsConflict() {
        Intervention a = new Intervention("lockdown", InterventionType.TRANSMISSION_REDUCTION, 0, 10, 0.5);
        Intervention b = new Intervention("lockdown", InterventionType.VACCINATION, 20, 30, 0.5);
        expectInvalid(() -> validator.validate(scenario().interventions(List.of(a, b)).build()),
                FailureReason.CONFLICTING_INTERVENTIONS);
    }

    @Test
    void acceptsOverlappingDistinctInterventions() {
        Intervention a = new Intervention("a", InterventionType.TRANSMISSION_REDUCTION, 0, 50, 0.3);
        Intervention b = new Intervention("b", InterventionType.TRANSMISSION_REDUCTION, 25, 75, 0.3);
        assertDoesNotThrow(() -> validator.validate(
                scenario().steps(100).interventions(List.of(a, b)).build()));
    }
}
