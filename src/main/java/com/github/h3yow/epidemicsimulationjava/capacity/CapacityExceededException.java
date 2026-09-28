package com.github.h3yow.epidemicsimulationjava.capacity;

/**
 * Thrown <em>before any computation starts</em> when a request exceeds a
 * configured resource ceiling. The exception is all-or-nothing: no tasks are
 * scheduled, no executor is created and no background work survives rejection.
 */
public class CapacityExceededException extends RuntimeException {

    private final CapacityDimension dimension;
    private final long requested;
    private final long limit;

    public CapacityExceededException(CapacityDimension dimension, long requested, long limit) {
        super(dimension.code() + ": requested " + requested + " exceeds limit " + limit);
        this.dimension = dimension;
        this.requested = requested;
        this.limit = limit;
    }

    /** @return which resource dimension was exceeded */
    public CapacityDimension dimension() {
        return dimension;
    }

    /** @return the requested magnitude */
    public long requested() {
        return requested;
    }

    /** @return the configured ceiling that was exceeded */
    public long limit() {
        return limit;
    }
}
