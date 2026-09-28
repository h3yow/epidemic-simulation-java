package com.github.h3yow.epidemicsimulationjava.api;

import com.github.h3yow.epidemicsimulationjava.batch.AggregateResult;
import com.github.h3yow.epidemicsimulationjava.batch.BatchCancelledException;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.batch.BatchRunner;
import com.github.h3yow.epidemicsimulationjava.model.InvalidScenarioException;
import com.github.h3yow.epidemicsimulationjava.simulation.ReplicationResult;
import com.github.h3yow.epidemicsimulationjava.simulation.SingleSimulationRequest;
import com.github.h3yow.epidemicsimulationjava.simulation.SimulationService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for scenario simulation and batch replication. */
@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private final BatchRunner batchRunner;
    private final SimulationService simulationService;

    public SimulationController(BatchRunner batchRunner, SimulationService simulationService) {
        this.batchRunner = batchRunner;
        this.simulationService = simulationService;
    }

    /** Runs one stochastic scenario replication. */
    @PostMapping("/single")
    public ReplicationResult runSingle(@RequestBody SingleSimulationRequest request) {
        return simulationService.run(request);
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

    @ExceptionHandler(BatchCancelledException.class)
    public ResponseEntity<Map<String, Object>> cancelled(BatchCancelledException e) {
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                .body(Map.of("error", e.isTimeout() ? "TIMEOUT" : "CANCELLED",
                        "message", e.getMessage()));
    }
}
