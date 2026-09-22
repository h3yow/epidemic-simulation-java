package com.github.h3yow.epidemicsimulationjava.batch;

/** Thrown when a batch is cancelled or times out. No partial results are ever returned. */
public class BatchCancelledException extends RuntimeException {

    private final boolean timeout;

    public BatchCancelledException(boolean timeout, String message) {
        super(message);
        this.timeout = timeout;
    }

    /** @return true if the batch ended because of a timeout rather than explicit cancellation */
    public boolean isTimeout() {
        return timeout;
    }
}
