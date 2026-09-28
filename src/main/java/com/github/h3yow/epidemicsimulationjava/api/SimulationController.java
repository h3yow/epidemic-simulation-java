package com.github.h3yow.epidemicsimulationjava.api;

import com.github.h3yow.epidemicsimulationjava.batch.AggregateResult;
import com.github.h3yow.epidemicsimulationjava.batch.BatchCancelledException;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRunner;
import com.github.h3yow.epidemicsimulationjava.capacity.CapacityDimension;
import com.github.h3yow.epidemicsimulationjava.capacity.CapacityExceededException;
import com.github.h3yow.epidemicsimulationjava.capacity.CapacityGuard;
import com.github.h3yow.epidemicsimulationjava.model.InvalidScenarioException;
import com.github.h3yow.epidemicsimulationjava.model.ScenarioValidator;
import com.github.h3yow.epidemicsimulationjava.simulation.ReplicationResult;
import com.github.h3yow.epidemicsimulationjava.simulation.SimulationEngine;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for single-scenario simulation and batch replication. */
@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);

    private final BatchRunner batchRunner;
    private final CapacityGuard capacityGuard;

    public SimulationController(BatchRunner batchRunner, CapacityGuard capacityGuard) {
        this.batchRunner = batchRunner;
        this.capacityGuard = capacityGuard;
    }

    /** Runs one stochastic replication of a single scenario. */
    @PostMapping("/run")
    public SimulationResponse runOnce(@RequestBody SimulationRequest request) {
        if (request == null || request.scenario() == null) {
            throw new IllegalArgumentException("request and scenario must not be null");
        }
        ScenarioValidator.validate(request.scenario());
        // Reject over-limit requests before any computation starts.
        capacityGuard.checkSimulation(request.scenario());
        log.info("simulation run start: seed={} population={} horizonHours={}",
                request.seed(), request.scenario().population(), request.scenario().horizonHours());
        ReplicationResult r = SimulationEngine.run(request.scenario(), request.seed(), 0);
        log.info("simulation run done: seed={} peakInfected={} totalRecovered={}",
                request.seed(), r.peakInfected(), r.totalRecovered());
        return new SimulationResponse(r.seed(), r.peakInfected(), r.totalRecovered(), r.hourlyInfected());
    }

    /** Runs a batch of stochastic replications and returns the aggregate result. */
    @PostMapping("/batch")
    public AggregateResult runBatch(@RequestBody BatchRequest request) {
        return batchRunner.run(request);
    }

    @ExceptionHandler(InvalidScenarioException.class)
    public ResponseEntity<Map<String, Object>> invalidScenario(InvalidScenarioException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "INVALID_SCENARIO", "reason", e.reason().name(),
                        "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }

    /** Over-limit requests are rejected with 422 and a machine-distinguishable dimension. */
    @ExceptionHandler(CapacityExceededException.class)
    public ResponseEntity<Map<String, Object>> capacityExceeded(CapacityExceededException e) {
        CapacityDimension dim = e.dimension();
        log.warn("request rejected by capacity guard: dimension={} requested={} limit={}",
                dim, e.requested(), e.limit());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of(
                        "error", "RESOURCE_LIMIT_EXCEEDED",
                        "reason", dim.name(),
                        "dimension", dim.name(),
                        "requested", e.requested(),
                        "limit", e.limit(),
                        "message", e.getMessage()));
    }

    @ExceptionHandler(BatchCancelledException.class)
    public ResponseEntity<Map<String, Object>> cancelled(BatchCancelledException e) {
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                .body(Map.of("error", e.isTimeout() ? "TIMEOUT" : "CANCELLED",
                        "message", e.getMessage()));
    }
}
