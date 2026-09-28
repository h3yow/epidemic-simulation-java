package com.github.h3yow.epidemicsimulationjava.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Serialises non-finite {@code double} values (NaN / +-Infinity) as JSON
 * {@code null} instead of the default quoted {@code "NaN"} string, so failed
 * calibration estimates are language-agnostic and keep their numeric meaning
 * ("absent") rather than arriving as a string in a number field.
 */
@Configuration
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer nonFiniteDoublesAsNull() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            JsonSerializer<Double> serializer = new JsonSerializer<>() {
                @Override
                public void serialize(Double value, JsonGenerator gen, SerializerProvider serializers)
                        throws IOException {
                    if (value == null || !Double.isFinite(value)) {
                        gen.writeNull();
                    } else {
                        gen.writeNumber(value);
                    }
                }
            };
            module.addSerializer(Double.class, serializer);
            module.addSerializer(Double.TYPE, serializer);
            builder.modules(module);
        };
    }
}
