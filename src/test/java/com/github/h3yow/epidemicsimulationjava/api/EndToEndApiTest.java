package com.github.h3yow.epidemicsimulationjava.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Real end-to-end walk-through over HTTP: single simulation, batch, calibration,
 * capacity rejection at every entry point, and noisy-observation calibration.
 * Also asserts the same calibration request gives identical conclusions
 * serially and in parallel.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndApiTest {

    private static final Logger log = LoggerFactory.getLogger(EndToEndApiTest.class);

    @LocalServerPort
    int port;

    private final TestRestTemplate http = new TestRestTemplate();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Map<String, Object> scenario() {
        return Map.of(
                "population", 5000,
                "initialInfected", 15,
                "beta", 0.35,
                "gamma", 0.08,
                "horizonHours", 120,
                "interventions", List.of());
    }

    @Test
    void singleSimulationRunsAndIsReproducibleBySeed() {
        Map<String, Object> body = Map.of("scenario", scenario(), "seed", 42);
        ResponseEntity<Map> r1 = http.postForEntity(url("/api/simulation/single"),
                new HttpEntity<>(body), Map.class);
        assertEquals(HttpStatus.OK, r1.getStatusCode());
        ResponseEntity<Map> r2 = http.postForEntity(url("/api/simulation/single"),
                new HttpEntity<>(body), Map.class);
        Number peak1 = (Number) r1.getBody().get("peakInfected");
        Number peak2 = (Number) r2.getBody().get("peakInfected");
        assertEquals(peak1.longValue(), peak2.longValue());
        assertEquals(42L, ((Number) r1.getBody().get("seed")).longValue());
        log.info("HTTP single: seed=42 peakInfected={}", peak1);
    }

    @Test
    void batchReturnsAggregatesAndRespectsSeedAcrossParallelism() {
        Map<String, Object> serialBody = Map.of("scenario", scenario(),
                "replications", 30, "masterSeed", 909L, "parallelism", 1, "timeoutMillis", 0);
        Map<String, Object> parallelBody = Map.of("scenario", scenario(),
                "replications", 30, "masterSeed", 909L, "parallelism", 8, "timeoutMillis", 0);
        ResponseEntity<Map> serial = http.postForEntity(url("/api/simulation/batch"),
                new HttpEntity<>(serialBody), Map.class);
        ResponseEntity<Map> parallel = http.postForEntity(url("/api/simulation/batch"),
                new HttpEntity<>(parallelBody), Map.class);
        assertEquals(HttpStatus.OK, serial.getStatusCode());
        assertEquals(((Number) serial.getBody().get("meanPeakInfected")).doubleValue(),
                ((Number) parallel.getBody().get("meanPeakInfected")).doubleValue(), 0.0);
        assertEquals(((Number) serial.getBody().get("meanTotalRecovered")).doubleValue(),
                ((Number) parallel.getBody().get("meanTotalRecovered")).doubleValue(), 0.0);
        log.info("HTTP batch: meanPeak serial={} parallel={} (equal)",
                serial.getBody().get("meanPeakInfected"),
                parallel.getBody().get("meanPeakInfected"));
    }

    @Test
    void calibrationSucceedsWithIntervalAndIsStableAcrossParallelism() {
        double[] observed = noisyObservations(60.0);
        Map<String, Object> reqP1 = calibrationRequest(observed, 1);
        Map<String, Object> reqP8 = calibrationRequest(observed, 8);
        ResponseEntity<Map> a = http.postForEntity(url("/api/calibration/beta"),
                new HttpEntity<>(reqP8), Map.class);
        ResponseEntity<Map> b = http.postForEntity(url("/api/calibration/beta"),
                new HttpEntity<>(reqP8), Map.class);
        ResponseEntity<Map> serial = http.postForEntity(url("/api/calibration/beta"),
                new HttpEntity<>(reqP1), Map.class);
        assertEquals(HttpStatus.OK, a.getStatusCode(), a.getBody().toString());
        assertEquals(Boolean.TRUE, a.getBody().get("success"));
        double beta = ((Number) a.getBody().get("estimatedBeta")).doubleValue();
        double lo = ((Number) a.getBody().get("betaLower95")).doubleValue();
        double hi = ((Number) a.getBody().get("betaUpper95")).doubleValue();
        assertTrue(lo < beta && beta < hi, "noisy interval must strictly bracket beta");
        assertTrue(hi - lo > 0.001, "noisy interval must not degenerate");
        // identical repeats, identical across parallelism
        assertEquals(beta, ((Number) b.getBody().get("estimatedBeta")).doubleValue(), 0.0);
        assertEquals(lo, ((Number) b.getBody().get("betaLower95")).doubleValue(), 0.0);
        assertEquals(hi, ((Number) b.getBody().get("betaUpper95")).doubleValue(), 0.0);
        assertEquals(beta, ((Number) serial.getBody().get("estimatedBeta")).doubleValue(), 0.0);
        assertEquals(lo, ((Number) serial.getBody().get("betaLower95")).doubleValue(), 0.0);
        assertEquals(hi, ((Number) serial.getBody().get("betaUpper95")).doubleValue(), 0.0);
        log.info("HTTP calibration noisy: beta={} CI95=[{}, {}] stable across repeats/parallelism",
                beta, lo, hi);
    }

    @Test
    void everyEntryPointRejectsOverCapacityRequestsBeforeRunning() {
        // single: population over ceiling
        Map<String, Object> bigScenario = Map.of(
                "population", 99_999_999L,
                "initialInfected", 15,
                "beta", 0.35, "gamma", 0.08,
                "horizonHours", 120, "interventions", List.of());
        ResponseEntity<Map> single = http.postForEntity(url("/api/simulation/single"),
                new HttpEntity<>(Map.of("scenario", bigScenario, "seed", 1)), Map.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, single.getStatusCode());
        assertEquals("CAPACITY_EXCEEDED", single.getBody().get("error"));
        assertEquals("POPULATION_TOO_LARGE", single.getBody().get("reason"));
        log.info("HTTP single capacity rejection: {}", single.getBody());

        // batch: too many replications
        Map<String, Object> batchBody = Map.of("scenario", scenario(),
                "replications", 5000, "masterSeed", 1L, "parallelism", 1, "timeoutMillis", 0);
        ResponseEntity<Map> batch = http.postForEntity(url("/api/simulation/batch"),
                new HttpEntity<>(batchBody), Map.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, batch.getStatusCode());
        assertEquals("TOO_MANY_REPLICATIONS", batch.getBody().get("reason"));
        log.info("HTTP batch capacity rejection: {}", batch.getBody());

        // calibration: aggregate work too large
        Map<String, Object> calBody = Map.of(
                "baseScenario", Map.of(
                        "population", 1000, "initialInfected", 15,
                        "beta", 0.3, "gamma", 0.05,
                        "horizonHours", 20_001, "interventions", List.of()),
                "observedInfected", new double[100],
                "betaLower", 0.1, "betaUpper", 0.8,
                "replications", 100, "masterSeed", 1L,
                "parallelism", 1, "maxEvaluations", 10);
        ResponseEntity<Map> cal = http.postForEntity(url("/api/calibration/beta"),
                new HttpEntity<>(calBody), Map.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, cal.getStatusCode());
        assertEquals(Boolean.FALSE, cal.getBody().get("success"));
        assertEquals("RESOURCE_LIMIT_EXCEEDED", cal.getBody().get("failureReason"));
        assertEquals("TOTAL_WORK_TOO_LARGE", cal.getBody().get("capacityReason"));
        log.info("HTTP calibration capacity rejection: failureReason={} capacityReason={}",
                cal.getBody().get("failureReason"), cal.getBody().get("capacityReason"));

        // The service must still serve normal requests immediately afterwards.
        ResponseEntity<Map> normal = http.postForEntity(url("/api/simulation/single"),
                new HttpEntity<>(Map.of("scenario", scenario(), "seed", 7)), Map.class);
        assertEquals(HttpStatus.OK, normal.getStatusCode());
        assertNotNull(normal.getBody().get("peakInfected"));
    }

    private Map<String, Object> calibrationRequest(double[] observed, int parallelism) {
        return Map.of(
                "baseScenario", Map.of(
                        "population", 5000, "initialInfected", 15,
                        "beta", 0.2, "gamma", 0.08,
                        "horizonHours", 120, "interventions", List.of()),
                "observedInfected", observed,
                "betaLower", 0.1, "betaUpper", 0.8,
                "replications", 300, "masterSeed", 777L,
                "parallelism", parallelism, "maxEvaluations", 200);
    }

    /** Generates noisy observations from the deterministic mean curve at beta=0.35. */
    private double[] noisyObservations(double noiseScale) {
        Map<String, Object> body = Map.of("scenario", scenario(),
                "replications", 300, "masterSeed", 777L, "parallelism", 4, "timeoutMillis", 0);
        ResponseEntity<Map> batch = http.postForEntity(url("/api/simulation/batch"),
                new HttpEntity<>(body), Map.class);
        assertEquals(HttpStatus.OK, batch.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) batch.getBody().get("results");
        int horizon = 120;
        double[] mean = new double[horizon];
        for (Map<String, Object> r : results) {
            @SuppressWarnings("unchecked")
            List<Number> hourly = (List<Number>) r.get("hourlyInfected");
            for (int t = 0; t < horizon; t++) {
                mean[t] += hourly.get(t).doubleValue();
            }
        }
        java.util.SplittableRandom rng = new java.util.SplittableRandom(4242L);
        double[] obs = new double[horizon];
        for (int t = 0; t < horizon; t++) {
            obs[t] = Math.max(0.0, mean[t] / results.size() + noiseScale * rng.nextGaussian());
        }
        return obs;
    }
}
