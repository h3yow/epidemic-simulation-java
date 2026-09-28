package com.github.h3yow.epidemicsimulationjava.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.h3yow.epidemicsimulationjava.batch.AggregateResult;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRunner;
import com.github.h3yow.epidemicsimulationjava.calibration.CalibrationRequest;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioConfig;
import com.github.h3yow.epidemicsimulationjava.simulation.ReplicationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end HTTP coverage through the real Spring context:
 * single run, batch (serial vs parallel identical), calibration on noisy data,
 * and capacity rejections on all three entries with distinguishable reasons.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(ApiEndToEndTest.class);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private BatchRunner batchRunner;

    private static ScenarioConfig scenario(double beta) {
        return new ScenarioConfig(5_000, 15, beta, 0.08, 120, List.of());
    }

    private static String scenarioJson(double beta) throws Exception {
        return new ObjectMapper().writeValueAsString(scenario(beta));
    }

    @Test
    void singleSimulationRunsAndReturnsTrajectory() throws Exception {
        String body = "{\"scenario\":" + scenarioJson(0.35) + ",\"seed\":42}";
        mvc.perform(post("/api/simulation/run").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(42))
                .andExpect(jsonPath("$.peakInfected").isNumber())
                .andExpect(jsonPath("$.hourlyInfected.length()").value(120));
    }

    @Test
    void batchSerialAndParallelReturnIdenticalAggregates() throws Exception {
        ScenarioConfig s = scenario(0.35);
        String serial = mapper.writeValueAsString(
                new BatchRequest(s, 24, 20260928L, 1, 0));
        String parallel = mapper.writeValueAsString(
                new BatchRequest(s, 24, 20260928L, 8, 0));

        MvcResult r1 = mvc.perform(post("/api/simulation/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(serial))
                .andExpect(status().isOk()).andReturn();
        MvcResult r2 = mvc.perform(post("/api/simulation/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(parallel))
                .andExpect(status().isOk()).andReturn();

        JsonNode a = mapper.readTree(r1.getResponse().getContentAsString());
        JsonNode b = mapper.readTree(r2.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(
                a.get("meanPeakInfected").asDouble(), b.get("meanPeakInfected").asDouble(), 0.0);
        org.junit.jupiter.api.Assertions.assertEquals(
                a.get("peakInfectedP2_5").asDouble(), b.get("peakInfectedP2_5").asDouble(), 0.0);
        org.junit.jupiter.api.Assertions.assertEquals(
                a.get("peakInfectedP97_5").asDouble(), b.get("peakInfectedP97_5").asDouble(), 0.0);
        org.junit.jupiter.api.Assertions.assertEquals(
                a.get("meanTotalRecovered").asDouble(), b.get("meanTotalRecovered").asDouble(), 0.0);
        log.info("HTTP batch parity serial/parallel: meanPeak={}", a.get("meanPeakInfected").asDouble());
    }

    @Test
    void calibrationOnNoisyObservationsSucceedsWithWideEnoughInterval() throws Exception {
        // Build noisy observations from a real mean curve + Gaussian noise.
        long masterSeed = 777L;
        int reps = 200;
        AggregateResult batch = batchRunner.run(
                new BatchRequest(scenario(0.35), reps, masterSeed, 4, 0));
        java.util.SplittableRandom rng = new java.util.SplittableRandom(31337L);
        double[] noisy = new double[120];
        double[] mean = new double[120];
        for (ReplicationResult r : batch.results()) {
            for (int h = 0; h < 120; h++) {
                mean[h] += r.hourlyInfected()[h];
            }
        }
        for (int h = 0; h < 120; h++) {
            noisy[h] = Math.max(0.0, mean[h] / reps + 30.0 * rng.nextGaussian());
        }
        CalibrationRequest req = new CalibrationRequest(scenario(0.2), noisy,
                0.1, 0.8, reps, masterSeed, 4, 400);
        String body = mapper.writeValueAsString(req);
        MvcResult result = mvc.perform(post("/api/calibration/beta")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.estimatedBeta").isNumber())
                .andExpect(jsonPath("$.noiseSigma").isNumber())
                .andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        double lo = json.get("betaLower95").asDouble();
        double hi = json.get("betaUpper95").asDouble();
        double beta = json.get("estimatedBeta").asDouble();
        log.info("HTTP noisy calibration: beta={} CI95=[{}, {}] width={} noiseSigma={}",
                beta, lo, hi, hi - lo, json.get("noiseSigma").asDouble());
        org.junit.jupiter.api.Assertions.assertTrue(lo < beta && beta < hi);
        org.junit.jupiter.api.Assertions.assertTrue(hi - lo > 1e-4,
                "noisy-data interval must not collapse to a point");
    }

    @Test
    void oversizedSingleSimulationIsRejectedWithDistinguishableReason() throws Exception {
        // horizonHours 100,000 > default 50,000; population within default.
        String hugeScenario = new ObjectMapper().writeValueAsString(
                new ScenarioConfig(5_000, 15, 0.35, 0.08, 100_000, List.of()));
        mvc.perform(post("/api/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":" + hugeScenario + ",\"seed\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RESOURCE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.reason").value("HORIZON_HOURS"))
                .andExpect(jsonPath("$.limit").value(50_000));
    }

    @Test
    void oversizedBatchIsRejectedWithDistinguishableReason() throws Exception {
        String body = mapper.writeValueAsString(new BatchRequest(scenario(0.35), 5_000, 1L, 4, 0));
        mvc.perform(post("/api/simulation/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RESOURCE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.reason").value("REPLICATIONS"))
                .andExpect(jsonPath("$.requested").value(5_000))
                .andExpect(jsonPath("$.limit").value(1_000));
    }

    @Test
    void oversizedCalibrationIsRejectedWithFailureReasonAndDetail() throws Exception {
        double[] observed = new double[120];
        String body = mapper.writeValueAsString(new CalibrationRequest(
                scenario(0.2), observed, 0.1, 0.8, 1_000, 1L, 4, 500));
        mvc.perform(post("/api/calibration/beta")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.failureReason").value("RESOURCE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.detailReason").value("TOTAL_SIMULATED_HOURS"));
    }

    @Test
    void invalidScenarioStillReturns400() throws Exception {
        String invalid = new ObjectMapper().writeValueAsString(
                new ScenarioConfig(0, 15, 0.35, 0.08, 120, List.of()));
        mvc.perform(post("/api/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":" + invalid + ",\"seed\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_POPULATION"));
    }

    @Test
    void failedCalibrationSerialisesNonFiniteFieldsAsJsonNull() throws Exception {
        double[] observed = new double[120];
        String body = mapper.writeValueAsString(new CalibrationRequest(
                scenario(0.2), observed, 0.1, 0.8, 1_000, 1L, 4, 500));
        MvcResult result = mvc.perform(post("/api/calibration/beta")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(json.get("estimatedBeta").isNull());
        org.junit.jupiter.api.Assertions.assertTrue(json.get("betaLower95").isNull());
        org.junit.jupiter.api.Assertions.assertTrue(json.get("noiseSigma").isNull());
        org.junit.jupiter.api.Assertions.assertEquals(0, json.get("evaluationsUsed").asInt());
    }
}
