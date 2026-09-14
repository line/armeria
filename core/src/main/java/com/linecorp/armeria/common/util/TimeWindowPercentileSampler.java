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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.metric.MoreMeters;

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
    private final long snapshotUpdateNanos;
    private static final long DEFAULT_SNAPSHOT_UPDATE_NANOS = TimeUnit.SECONDS.toNanos(1);
    private long lastSnapshotNanos;
    private HistogramSnapshot histogramSnapshot;

    private final Clock clock;
    private final AtomicReference<Boolean> isTakingSnapshot = new AtomicReference<>(false);

    TimeWindowPercentileSampler(float percentile, long windowLengthMillis) {
        this(percentile, windowLengthMillis, Clock.SYSTEM, DEFAULT_SNAPSHOT_UPDATE_NANOS);
    }

    @VisibleForTesting
    TimeWindowPercentileSampler(float percentile, long windowLengthMillis, Clock clock,
                                long snapshotUpdateNanos) {
        this.percentile = percentile;
        this.windowLengthMillis = windowLengthMillis;

        final DistributionStatisticConfig distributionStatisticConfig =
                DistributionStatisticConfig.builder()
                                           .percentilesHistogram(false)
                                           .percentiles(percentile)
                                           .expiry(Duration.ofMillis(windowLengthMillis))
                                           .build()
                                           .merge(MoreMeters.distributionStatisticConfig());
        this.histogram = new TimeWindowPercentileHistogram(clock, distributionStatisticConfig, true);
        this.snapshotUpdateNanos = snapshotUpdateNanos;
        this.histogramSnapshot = histogram.takeSnapshot(0, 0, 0);
        this.clock = clock;
        this.lastSnapshotNanos = clock.monotonicTime();
    }

    @VisibleForTesting
    static TimeWindowPercentileSampler create(float percentile, long windowLengthMillis,
                                              long snapshotUpdateNanos) {
        return new TimeWindowPercentileSampler(percentile, windowLengthMillis, Clock.SYSTEM,
                                               snapshotUpdateNanos);
    }

    @Override
    public boolean isSampled(Long t) {
        histogram.recordLong(t);

        System.out.println("lastSnapshotNanos: " + lastSnapshotNanos);
        System.out.println("snapshotUpdateNanos: " + snapshotUpdateNanos);
        System.out.println("clock.monotonicTime(): " + clock.monotonicTime());

        if (lastSnapshotNanos + snapshotUpdateNanos <= clock.monotonicTime()) {
            if (isTakingSnapshot.compareAndSet(false, true)) {
                // Two threads reach here back to back. Make sure snapshot is not taken very recently before
                // we acquired the lock.
                if (lastSnapshotNanos + snapshotUpdateNanos <= clock.monotonicTime()) {
                    System.out.println("Taking snapshot");
                    histogramSnapshot = histogram.takeSnapshot(0, 0, 0);
                    lastSnapshotNanos = clock.monotonicTime();
                    isTakingSnapshot.set(false);
                }
            }
        }

        final Double percentileValue = histogramSnapshot.percentileValues()[0].value();
        return t >= percentileValue.longValue();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("percentile", percentile)
                          .add("windowLengthMillis", windowLengthMillis)
                          .add("snapshotUpdateNanos", snapshotUpdateNanos)
                          .toString();
    }
}
