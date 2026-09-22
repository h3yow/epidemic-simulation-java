package com.github.h3yow.epidemicsimulationjava.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.epidemicsimulationjava.model.InvalidScenarioException.Reason;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Validation rules: window boundaries, illegal values, conflicting interventions. */
class ScenarioValidatorTest {

    private static final Logger log = LoggerFactory.getLogger(ScenarioValidatorTest.class);

    private static ScenarioConfig scenario(List<Intervention> interventions) {
        return new ScenarioConfig(10_000, 10, 0.3, 0.1, 240, interventions);
    }

    @Test
    void validScenarioPasses() {
        assertDoesNotThrow(() -> ScenarioValidator.validate(scenario(List.of(
                new Intervention(InterventionType.SOCIAL_DISTANCING, 10, 20, 0.5),
                new Intervention(InterventionType.MASKING, 15, 25, 0.8))))); // different types may overlap
    }

    @Test
    void windowTouchingHorizonEndIsValid() {
        // [0, 240) with horizon 240: endHour == horizon is the last valid boundary.
        assertDoesNotThrow(() -> ScenarioValidator.validate(scenario(List.of(
                new Intervention(InterventionType.MASKING, 0, 240, 0.9)))));
    }

    @Test
    void windowBeyondHorizonIsRejected() {
        var e = assertThrows(InvalidScenarioException.class,
                () -> ScenarioValidator.validate(scenario(List.of(
                        new Intervention(InterventionType.MASKING, 0, 241, 0.9)))));
        assertEquals(Reason.WINDOW_OUT_OF_HORIZON, e.reason());
        log.info("rejected as {}: {}", e.reason(), e.getMessage());
    }

    @Test
    void emptyOrInvertedWindowIsRejected() {
        var empty = assertThrows(InvalidScenarioException.class,
                () -> ScenarioValidator.validate(scenario(List.of(
                        new Intervention(InterventionType.MASKING, 10, 10, 0.9)))));
        assertEquals(Reason.INVALID_WINDOW, empty.reason());

        var inverted = assertThrows(InvalidScenarioException.class,
                () -> ScenarioValidator.validate(scenario(List.of(
                        new Intervention(InterventionType.MASKING, 20, 10, 0.9)))));
        assertEquals(Reason.INVALID_WINDOW, inverted.reason());

        var negative = assertThrows(InvalidScenarioException.class,
                () -> ScenarioValidator.validate(scenario(List.of(
                        new Intervention(InterventionType.MASKING, -1, 10, 0.9)))));
        assertEquals(Reason.INVALID_WINDOW, negative.reason());
    }

    @Test
    void duplicateSameTypeWindowsAreRejectedAsConflict() {
        Intervention iv = new Intervention(InterventionType.SOCIAL_DISTANCING, 10, 20, 0.5);
        var e = assertThrows(InvalidScenarioException.class,
                () -> ScenarioValidator.validate(scenario(List.of(iv, iv))));
        assertEquals(Reason.CONFLICTING_INTERVENTIONS, e.reason());
        log.info("duplicate rejected as {}: {}", e.reason(), e.getMessage());
    }

    @Test
    void overlappingSameTypeWindowsAreRejectedAsConflict() {
        var e = assertThrows(InvalidScenarioException.class,
                () -> ScenarioValidator.validate(scenario(List.of(
                        new Intervention(InterventionType.MASKING, 10, 30, 0.5),
                        new Intervention(InterventionType.MASKING, 20, 40, 0.7)))));
        assertEquals(Reason.CONFLICTING_INTERVENTIONS, e.reason());
    }

    @Test
    void adjacentSameTypeWindowsAreAllowed() {
        // [10,20) and [20,30) do not overlap under half-open semantics.
        assertDoesNotThrow(() -> ScenarioValidator.validate(scenario(List.of(
                new Intervention(InterventionType.MASKING, 10, 20, 0.5),
                new Intervention(InterventionType.MASKING, 20, 30, 0.7)))));
    }

    @Test
    void illegalScalarParametersAreRejectedWithDistinctReasons() {
        assertEquals(Reason.INVALID_POPULATION, assertThrows(InvalidScenarioException.class,
                () -> ScenarioValidator.validate(
                        new ScenarioConfig(0, 1, 0.3, 0.1, 100, List.of()))).reason());
        assertEquals(Reason.INVALID_INITIAL_INFECTED, assertThrows(InvalidScenarioException.class,
                () -> ScenarioValidator.validate(
                        new ScenarioConfig(100, 100, 0.3, 0.1, 100, List.of()))).reason());
        assertEquals(Reason.INVALID_RATE, assertThrows(InvalidScenarioException.class,
                () -> ScenarioValidator.validate(
                        new ScenarioConfig(100, 1, -0.3, 0.1, 100, List.of()))).reason());
        assertEquals(Reason.INVALID_RATE, assertThrows(InvalidScenarioException.class,
                () -> ScenarioValidator.validate(
                        new ScenarioConfig(100, 1, 0.3, 1.5, 100, List.of()))).reason());
        assertEquals(Reason.INVALID_HORIZON, assertThrows(InvalidScenarioException.class,
                () -> ScenarioValidator.validate(
                        new ScenarioConfig(100, 1, 0.3, 0.1, 0, List.of()))).reason());
        assertEquals(Reason.INVALID_EFFECT, assertThrows(InvalidScenarioException.class,
                () -> ScenarioValidator.validate(scenario(List.of(
                        new Intervention(InterventionType.MASKING, 0, 10, 1.5))))).reason());
        assertEquals(Reason.INVALID_EFFECT, assertThrows(InvalidScenarioException.class,
                () -> ScenarioValidator.validate(scenario(List.of(
                        new Intervention(InterventionType.MASKING, 0, 10, 0.0))))).reason());
    }

    @Test
    void activeAtImplementsHalfOpenWindow() {
        Intervention iv = new Intervention(InterventionType.MASKING, 10, 20, 0.5);
        assertFalse(iv.activeAt(9));
        assertTrue(iv.activeAt(10));   // start boundary: active
        assertTrue(iv.activeAt(19));
        assertFalse(iv.activeAt(20));  // end boundary: inactive
    }
}
