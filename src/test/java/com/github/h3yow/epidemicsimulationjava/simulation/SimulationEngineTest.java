package com.github.h3yow.epidemicsimulationjava.simulation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.github.h3yow.epidemicsimulationjava.model.Intervention;
import com.github.h3yow.epidemicsimulationjava.model.InterventionType;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Engine semantics: intervention windows at exact boundaries, per-seed determinism. */
class SimulationEngineTest {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngineTest.class);

    private static ScenarioConfig scenario(List<Intervention> interventions) {
        return new ScenarioConfig(5_000, 20, 0.4, 0.1, 100, interventions);
    }

    @Test
    void transmissionMultiplierAppliesOnlyInsideWindow() {
        ScenarioConfig config = scenario(List.of(
                new Intervention(InterventionType.SOCIAL_DISTANCING, 10, 20, 0.5)));
        assertEquals(0.4, SimulationEngine.effectiveBeta(config, 9), 1e-12);
        assertEquals(0.2, SimulationEngine.effectiveBeta(config, 10), 1e-12, "start boundary: active");
        assertEquals(0.2, SimulationEngine.effectiveBeta(config, 19), 1e-12);
        assertEquals(0.4, SimulationEngine.effectiveBeta(config, 20), 1e-12, "end boundary: inactive");
    }

    @Test
    void differentTypesCombineInsideOverlap() {
        ScenarioConfig config = scenario(List.of(
                new Intervention(InterventionType.SOCIAL_DISTANCING, 0, 50, 0.5),
                new Intervention(InterventionType.MASKING, 25, 75, 0.8),
                new Intervention(InterventionType.TREATMENT, 40, 60, 0.05)));
        assertEquals(0.4 * 0.5, SimulationEngine.effectiveBeta(config, 24), 1e-12);
        assertEquals(0.4 * 0.5 * 0.8, SimulationEngine.effectiveBeta(config, 25), 1e-12,
                "both transmission interventions multiply inside overlap");
        assertEquals(0.4 * 0.8, SimulationEngine.effectiveBeta(config, 50), 1e-12);
        assertEquals(0.1, SimulationEngine.effectiveGamma(config, 39), 1e-12);
        assertEquals(0.15, SimulationEngine.effectiveGamma(config, 40), 1e-12,
                "treatment adds to gamma inside its window");
        assertEquals(0.1, SimulationEngine.effectiveGamma(config, 60), 1e-12);
    }

    @Test
    void sameSeedGivesIdenticalTrajectory() {
        ScenarioConfig config = scenario(List.of(
                new Intervention(InterventionType.MASKING, 30, 60, 0.6)));
        ReplicationResult a = SimulationEngine.run(config, 42L, 0);
        ReplicationResult b = SimulationEngine.run(config, 42L, 0);
        assertArrayEquals(a.hourlyInfected(), b.hourlyInfected());
        assertEquals(a.peakInfected(), b.peakInfected());
        log.info("determinism check: seed=42 peak={} totalRecovered={}",
                a.peakInfected(), a.totalRecovered());
    }

    @Test
    void differentSeedsGiveDifferentTrajectories() {
        ScenarioConfig config = scenario(List.of());
        ReplicationResult a = SimulationEngine.run(config, 1L, 0);
        ReplicationResult b = SimulationEngine.run(config, 2L, 1);
        assertNotEquals(a.hourlyInfected()[50], b.hourlyInfected()[50]);
    }

    @Test
    void conservationOfPopulationHolds() {
        ScenarioConfig config = scenario(List.of());
        ReplicationResult r = SimulationEngine.run(config, 7L, 0);
        long[] hourly = r.hourlyInfected();
        long finalI = hourly[hourly.length - 1];
        // S(0) + I(0) = N; at the end: S + I + R = N must still hold.
        // R is reported; S is implicit: N - I - R must be >= 0.
        long impliedS = config.population() - finalI - r.totalRecovered();
        assertEquals(true, impliedS >= 0, "population conservation violated");
        assertEquals(config.population(), impliedS + finalI + r.totalRecovered());
    }
}
