/*
 * Copyright 2023 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 */
package com.linecorp.armeria.common.util;

import java.util.concurrent.TimeUnit;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.SlidingTimeWindowReservoir;

/**
 * Sample if the value is less than the percentile of the values in the last window.
 */
final class SlidingWindowPercentileSampler implements Sampler<Long> {

    private final float percentile;
    private final long windowLengthMillis;

    private final Histogram histogram;

    SlidingWindowPercentileSampler(float percentile, long windowLengthMillis) {
        this.percentile = percentile;
        this.windowLengthMillis = windowLengthMillis;

        // TODO: Check memory footprint, try limiting resources.
        final SlidingTimeWindowReservoir reservoir = new SlidingTimeWindowReservoir(windowLengthMillis,
                                                                                    TimeUnit.MILLISECONDS);
        this.histogram = new Histogram(reservoir);
    }

    static SlidingWindowPercentileSampler create(float percentile, long windowLengthMillis) {
        return new SlidingWindowPercentileSampler(percentile, windowLengthMillis);
    }

    @Override
    public boolean isSampled(Long t) {
        histogram.update(t);
        // TODO: get snapshot calls might be expensive, consider caching snapshot
        return histogram.getSnapshot().getValue(this.percentile) <= t;
    }

    @Override
    public String toString() {
        return "SlidingWindowPercentileSampler with " + windowLengthMillis + " ms window and " + percentile +
               " percentile";
    }
}
