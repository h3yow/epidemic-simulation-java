package com.github.h3yow.epidemicsimulationjava.capacity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externally configurable capacity bounds under the {@code app.capacity} prefix.
 *
 * <p>Every field can be set through {@code application.properties}, Spring profiles,
 * environment variables ({@code APP_CAPACITY_MAX_REPLICATIONS}), {@code -D} system
 * properties, etc. Unset fields fall back to {@link CapacityLimits#defaults()}.
 */
@ConfigurationProperties(prefix = "app.capacity")
public record CapacityProperties(
        Long maxPopulation,
        Long maxHorizonHours,
        Long maxReplications,
        Long maxParallelism,
        Long maxCalibrationEvaluations,
        Long maxTotalSimulatedHours) {

    public CapacityProperties {
        CapacityLimits d = CapacityLimits.defaults();
        maxPopulation = maxPopulation != null ? maxPopulation : d.maxPopulation();
        maxHorizonHours = maxHorizonHours != null ? maxHorizonHours : d.maxHorizonHours();
        maxReplications = maxReplications != null ? maxReplications : d.maxReplications();
        maxParallelism = maxParallelism != null ? maxParallelism : d.maxParallelism();
        maxCalibrationEvaluations = maxCalibrationEvaluations != null
                ? maxCalibrationEvaluations : d.maxCalibrationEvaluations();
        maxTotalSimulatedHours = maxTotalSimulatedHours != null
                ? maxTotalSimulatedHours : d.maxTotalSimulatedHours();
        if (maxPopulation < 1 || maxHorizonHours < 1 || maxReplications < 1
                || maxParallelism < 1 || maxCalibrationEvaluations < 1
                || maxTotalSimulatedHours < 1) {
            throw new IllegalArgumentException(
                    "app.capacity.* bounds must all be positive: " + this);
        }
    }

    /** @return the effective immutable limits */
    public CapacityLimits toLimits() {
        return new CapacityLimits(maxPopulation, maxHorizonHours, maxReplications,
                maxParallelism, maxCalibrationEvaluations, maxTotalSimulatedHours);
    }
}
