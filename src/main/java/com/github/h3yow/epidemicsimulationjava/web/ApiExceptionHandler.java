package com.github.h3yow.epidemicsimulationjava.web;

import com.github.h3yow.epidemicsimulationjava.exception.SimulationException;
import com.github.h3yow.epidemicsimulationjava.model.FailureReason;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将带原因的业务异常映射为可区分的 HTTP 响应：
 * 客户端可依据 reason 字段精确区分失败类别，而非笼统的 500。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(SimulationException.class)
    public ResponseEntity<Map<String, Object>> handle(SimulationException ex) {
        HttpStatus status = statusFor(ex.getReason());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", ex.getReason().name());
        body.put("message", ex.getMessage());
        log.warn("request failed: reason={} message={}", ex.getReason(), ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    private static HttpStatus statusFor(FailureReason reason) {
        return switch (reason) {
            case INVALID_SCENARIO, INVALID_INTERVENTION, CONFLICTING_INTERVENTIONS,
                 INVALID_CALIBRATION_REQUEST, INSUFFICIENT_DATA, NO_CONVERGENCE ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case RESOURCE_LIMIT_EXCEEDED -> HttpStatus.PAYLOAD_TOO_LARGE;
            case CANCELLED -> HttpStatus.CONFLICT;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
        };
    }
}
