package com.github.h3yow.epidemicsimulationjava.tests;

import com.github.h3yow.epidemicsimulationjava.model.EpidemicScenario;
import com.github.h3yow.epidemicsimulationjava.model.Intervention;
import java.util.List;

/** 测试用情景构造辅助。 */
final class TestScenarios {

    private TestScenarios() {
    }

    static final class Builder {
        private String name = "test";
        private int population = 1_000;
        private int initialExposed = 5;
        private int initialInfectious = 5;
        private double beta = 0.05;
        private double sigma = 0.2;
        private double gamma = 0.1;
        private int steps = 100;
        private List<Intervention> interventions = List.of();

        Builder name(String v) { this.name = v; return this; }
        Builder population(int v) { this.population = v; return this; }
        Builder initialExposed(int v) { this.initialExposed = v; return this; }
        Builder initialInfectious(int v) { this.initialInfectious = v; return this; }
        Builder beta(double v) { this.beta = v; return this; }
        Builder sigma(double v) { this.sigma = v; return this; }
        Builder gamma(double v) { this.gamma = v; return this; }
        Builder steps(int v) { this.steps = v; return this; }
        Builder interventions(List<Intervention> v) { this.interventions = v; return this; }

        EpidemicScenario build() {
            return new EpidemicScenario(name, population, initialExposed, initialInfectious,
                    beta, sigma, gamma, steps, interventions);
        }
    }
}
