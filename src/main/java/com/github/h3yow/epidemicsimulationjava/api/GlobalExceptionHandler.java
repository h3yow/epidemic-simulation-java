package com.github.h3yow.epidemicsimulationjava.api;

import com.github.h3yow.epidemicsimulationjava.capacity.CapacityExceededException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates capacity rejections into a uniform 422 response for every
 * compute-heavy entry point. The {@code reason} field carries the
 * machine-distinguishable dimension code; no other handler will swallow it.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CapacityExceededException.class)
    public ResponseEntity<Map<String, Object>> capacityExceeded(CapacityExceededException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of(
                        "error", "CAPACITY_EXCEEDED",
                        "reason", e.dimension().code(),
                        "requested", e.requested(),
                        "limit", e.limit(),
                        "message", e.getMessage()));
    }
}
