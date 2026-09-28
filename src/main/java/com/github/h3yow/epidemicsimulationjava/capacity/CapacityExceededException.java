package com.github.h3yow.epidemicsimulationjava.capacity;

/**
 * Thrown when a request exceeds a configured capacity bound.
 *
 * <p>Always thrown <em>before</em> any heavy computation starts; no task is
 * scheduled and no background work survives such a rejection.
 */
public class CapacityExceededException extends RuntimeException {

    private final CapacityDimension dimension;
    private final long requested;
    private final long limit;

    public CapacityExceededException(CapacityDimension dimension, long requested, long limit, String message) {
        super(message);
        this.dimension = dimension;
        this.requested = requested;
        this.limit = limit;
    }

    /** @return which resource dimension was exceeded (machine-distinguishable code) */
    public CapacityDimension dimension() {
        return dimension;
    }

    /** @return the requested amount */
    public long requested() {
        return requested;
    }

    /** @return the configured bound that was violated */
    public long limit() {
        return limit;
    }
}
