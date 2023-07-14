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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.annotations.VisibleForTesting;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.TimeWindowPercentileHistogram;

/**
 * Sample if the value is less than the percentile of the values in the last window.
 */
final class TimeWindowPercentileSampler implements Sampler<Long> {

    private final float percentile;
    private final long windowLengthMillis;
    private final TimeWindowPercentileHistogram histogram;
    private static final long SNAPSHOT_UPDATE_MILLIS = 1000L;
    private long lastSnapshotMillis;
    private HistogramSnapshot histogramSnapshot;

    private final AtomicReference<Boolean> isTakingSnapshot = new AtomicReference<>(false);

    TimeWindowPercentileSampler(float percentile, long windowLengthMillis) {
        this(percentile, windowLengthMillis, Clock.SYSTEM);
    }

    @VisibleForTesting
    TimeWindowPercentileSampler(float percentile, long windowLengthMillis, Clock clock) {
        this.percentile = percentile;
        this.windowLengthMillis = windowLengthMillis;

        final DistributionStatisticConfig distributionStatisticConfig =
                DistributionStatisticConfig.builder()
                                           .percentiles(percentile)
                                           .percentilePrecision(2)
                                           .minimumExpectedValue(1.0)
                                           .maximumExpectedValue(Double.POSITIVE_INFINITY)
                                           .expiry(Duration.ofMillis(windowLengthMillis))
                                           .bufferLength(3)
                                           .build();
        this.histogram = new TimeWindowPercentileHistogram(clock, distributionStatisticConfig, true);
        this.histogramSnapshot = histogram.takeSnapshot(0, 0, 0);
        this.lastSnapshotMillis = System.currentTimeMillis();
    }

    static TimeWindowPercentileSampler create(float percentile, long windowLengthMillis) {
        return new TimeWindowPercentileSampler(percentile, windowLengthMillis);
    }

    @Override
    public boolean isSampled(Long t) {
        histogram.recordLong(t);

        if (lastSnapshotMillis + SNAPSHOT_UPDATE_MILLIS < System.currentTimeMillis()) {
            if (isTakingSnapshot.compareAndSet(false, true)) {
                histogramSnapshot = histogram.takeSnapshot(0, 0, 0);
                lastSnapshotMillis = System.currentTimeMillis();
                isTakingSnapshot.set(false);
            }
        }

        final Double percentileValue = histogramSnapshot.percentileValues()[0].value();
        return t >= percentileValue.longValue();
    }

    @Override
    public String toString() {
        return "TimeWindowPercentileSampler with " + windowLengthMillis + " ms window and " + percentile +
               " percentile";
    }
}
