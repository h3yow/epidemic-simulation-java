package com.github.h3yow.epidemicsimulationjava.web;

import com.github.h3yow.epidemicsimulationjava.batch.BatchRunner;
import com.github.h3yow.epidemicsimulationjava.calibration.CalibrationService;
import com.github.h3yow.epidemicsimulationjava.model.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.model.BatchResult;
import com.github.h3yow.epidemicsimulationjava.model.CalibrationRequest;
import com.github.h3yow.epidemicsimulationjava.model.CalibrationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 批量实验与参数校准的 REST 入口。 */
@RestController
@RequestMapping("/api")
public class SimulationController {

    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);

    private final BatchRunner batchRunner;
    private final CalibrationService calibrationService;

    public SimulationController(BatchRunner batchRunner, CalibrationService calibrationService) {
        this.batchRunner = batchRunner;
        this.calibrationService = calibrationService;
    }

    @PostMapping("/simulations/batch")
    public BatchResult runBatch(@RequestBody BatchRequest request) {
        log.info("batch request: baseSeed={} parallelism={} replications={} timeout={}",
                request.baseSeed(), request.parallelism(), request.replications(), request.timeout());
        return batchRunner.run(request);
    }

    @PostMapping("/calibrations")
    public CalibrationResult calibrate(@RequestBody CalibrationRequest request) {
        log.info("calibration request: betaRange=[{},{}] gridPoints={} replicationsPerPoint={} observations={}",
                request.betaLow(), request.betaHigh(), request.gridPoints(),
                request.replicationsPerPoint(),
                request.observations() == null ? 0 : request.observations().size());
        return calibrationService.calibrate(request);
    }
}
