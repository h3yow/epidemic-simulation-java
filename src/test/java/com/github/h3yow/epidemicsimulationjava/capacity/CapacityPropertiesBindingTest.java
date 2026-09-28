package com.github.h3yow.epidemicsimulationjava.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootTest(properties = {
        "epidemic.capacity.max-replications=20",
        "epidemic.capacity.max-population=999"
})
class CapacityPropertiesBindingTest {

    @Autowired
    CapacityLimits limits;

    @Test
    void propertiesOverrideDefaults() {
        assertEquals(20, limits.maxReplications());
        assertEquals(999L, limits.maxPopulation());
    }
}
