package com.github.h3yow.epidemicsimulationjava.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.h3yow.epidemicsimulationjava.batch.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Externalised configuration: overriding {@code app.capacity.*} via Spring
 * properties (the same mechanism as env vars / -D / application.properties)
 * changes the actually enforced limits without any code change.
 */
@SpringBootTest(properties = {
        "app.capacity.max-replications=7",
        "app.capacity.max-parallelism=3",
        "app.capacity.max-horizon-hours=333"
})
class CapacityPropertiesOverrideTest {

    @Autowired
    private CapacityGuard guard;

    @Test
    void overriddenBoundsAreEnforced() {
        assertEquals(7L, guard.limits().maxReplications());
        assertEquals(3L, guard.limits().maxParallelism());
        assertEquals(333L, guard.limits().maxHorizonHours());

        ScenarioConfig scenario = new ScenarioConfig(1_000, 10, 0.3, 0.05, 333, List.of());
        // 8 replications > overridden cap of 7, though far below the default 1,000.
        CapacityExceededException e = assertThrows(CapacityExceededException.class,
                () -> guard.checkBatch(new BatchRequest(scenario, 8, 1L, 2, 0)));
        assertEquals(CapacityDimension.REPLICATIONS, e.dimension());
        assertEquals(8, e.requested());
        assertEquals(7, e.limit());
    }

    @Test
    void untouchedDimensionsKeepDefaults() {
        assertEquals(CapacityLimits.defaults().maxPopulation(), guard.limits().maxPopulation());
        assertEquals(CapacityLimits.defaults().maxCalibrationEvaluations(),
                guard.limits().maxCalibrationEvaluations());
        assertEquals(CapacityLimits.defaults().maxTotalSimulatedHours(),
                guard.limits().maxTotalSimulatedHours());
    }
}
