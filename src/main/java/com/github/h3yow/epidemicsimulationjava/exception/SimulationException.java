package com.github.h3yow.epidemicsimulationjava.exception;

import com.github.h3yow.epidemicsimulationjava.model.FailureReason;

/** 带可区分失败原因的业务异常基类。 */
public class SimulationException extends RuntimeException {
    private final FailureReason reason;

    public SimulationException(FailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SimulationException(FailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public FailureReason getReason() {
        return reason;
    }
}
