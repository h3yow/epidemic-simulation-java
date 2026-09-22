package com.github.h3yow.epidemicsimulationjava.api;

import com.github.h3yow.epidemicsimulationjava.calibration.CalibrationRequest;
import com.github.h3yow.epidemicsimulationjava.calibration.CalibrationResult;
import com.github.h3yow.epidemicsimulationjava.calibration.Calibrator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoint for parameter calibration. */
@RestController
@RequestMapping("/api/calibration")
public class CalibrationController {

    private final Calibrator calibrator;

    public CalibrationController(Calibrator calibrator) {
        this.calibrator = calibrator;
    }

    /**
     * Calibrates beta against observed data. Failed calibrations return HTTP 422
     * with a machine-distinguishable failureReason in the body.
     */
    @PostMapping("/beta")
    public ResponseEntity<CalibrationResult> calibrate(@RequestBody CalibrationRequest request) {
        CalibrationResult result = calibrator.calibrate(request);
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(result);
    }
}
