/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
/*
 *  Copyright 2016 Vladimir Bukhtoyarov
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.linecorp.armeria.common.metric;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

import org.HdrHistogram.Recorder;

import com.codahale.metrics.Snapshot;
import com.github.rollingmetrics.histogram.accumulator.Accumulator;
import com.github.rollingmetrics.histogram.accumulator.ResetByChunksAccumulator;
import com.github.rollingmetrics.util.Clock;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * Default {@link Histogram} implementation based on <a href="http://hdrhistogram.org/">HdrHistogram</a>.
 */
public final class DefaultHistogram extends AbstractMetric implements Histogram {

    private static final int DEFAULT_SIGNIFICANT_DIGITS = 2;
    private static final int DEFAULT_MAX_AGE_MINS = 10;
    private static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(DEFAULT_MAX_AGE_MINS);
    private static final int DEFAULT_BUCKETS = 5;

    static final double[] QUANTILES = { 0.5, 0.75, 0.95, 0.98, 0.99, 0.999 };

    private final LongAdder count = new LongAdder();
    private final LongAdder sum = new LongAdder();
    private final Accumulator accumulator;

    /**
     * Creates a new instance with the default parameters. The default parameters are:
     * <ul>
     *   <li>{@code significantDigits} - {@value #DEFAULT_SIGNIFICANT_DIGITS}</li>
     *   <li>{@code maxAge} - {@value #DEFAULT_MAX_AGE_MINS} minutes</li>
     *   <li>{@code numBuckets} - {@value #DEFAULT_BUCKETS}</li>
     * </ul>
     *
     * @param key the {@link MetricKey}
     * @param unit the {@link MetricUnit}
     * @param description the human-readable description
     */
    public DefaultHistogram(MetricKey key, MetricUnit unit, String description) {
        this(key, unit, description,
             DEFAULT_SIGNIFICANT_DIGITS, DEFAULT_MAX_AGE, DEFAULT_BUCKETS);
    }

    /**
     * Creates a new instance.
     *
     * @param key the {@link MetricKey}
     * @param unit the {@link MetricUnit}
     * @param description the human-readable description
     * @param significantDigits the number of significant decimal digits to which the histogram will maintain
     *                          value resolution and separation. Must be between 0 and 5.
     * @param maxAge the duration of the time window
     * @param numBuckets the number of buckets used to implement the sliding time window
     */
    public DefaultHistogram(MetricKey key, MetricUnit unit, String description,
                            int significantDigits, Duration maxAge, int numBuckets) {

        super(key, unit, description);
        validateHistogramParameters(significantDigits, maxAge, numBuckets);

        accumulator = new ResetByChunksAccumulator(() -> new Recorder(significantDigits),
                                                   numBuckets, maxAge.toMillis() / numBuckets,
                                                   Clock.defaultClock(), MoreExecutors.directExecutor());
    }

    private static void validateHistogramParameters(int significantDigits, Duration maxAge, int numBuckets) {
        requireNonNull(maxAge, "maxAge");
        checkArgument(significantDigits >= 0 && significantDigits <= 5,
                      "significantDigits: %s (expected: 0..5)", significantDigits);
        checkArgument(numBuckets >= 2, "numBuckets: %s (expected: >= 2)", numBuckets);

        final boolean invalidWindow;
        if (numBuckets > 0) {
            final long millisPerBucket = maxAge.toMillis() / numBuckets;
            invalidWindow = millisPerBucket < 1000;
        } else {
            invalidWindow = true;
        }

        if (invalidWindow) {
            throw new IllegalArgumentException(
                    "maxAge (" + maxAge + ") / numBuckets (" + numBuckets +
                    ") must be greater than 1 second.");
        }
    }

    /**
     * Adds a recorded value.
     */
    public void update(long value) {
        count.increment();
        sum.add(value);
        accumulator.recordSingleValueWithExpectedInterval(value, 0);
    }

    @Override
    public long count() {
        return count.sum();
    }

    @Override
    public long sum() {
        return sum.sum();
    }

    @Override
    public HistogramSnapshot snapshot() {
        final Snapshot snapshot = accumulator.getSnapshot(histogram -> takeSnapshot(QUANTILES, histogram));
        if (snapshot.size() == 0) {
            return DefaultHistogramSnapshot.EMPTY;
        } else {
            return (HistogramSnapshot) snapshot;
        }
    }

    private static DefaultHistogramSnapshot takeSnapshot(final double[] predefinedQuantiles,
                                                         org.HdrHistogram.Histogram histogram) {
        final long max = histogram.getMaxValue();
        final long min = histogram.getMinValue();
        final double mean = histogram.getMean();
        final double median = histogram.getValueAtPercentile(50.0);
        final double stdDeviation = histogram.getStdDeviation();

        final double[] values = new double[predefinedQuantiles.length];
        for (int i = 0; i < predefinedQuantiles.length; i++) {
            values[i] = histogram.getValueAtPercentile(predefinedQuantiles[i] * 100.0);
        }

        return new DefaultHistogramSnapshot(predefinedQuantiles, values, min, max, mean, median, stdDeviation);
    }

    @Override
    public String toString() {
        return new StringBuilder().append(key())
                                  .append('=')
                                  .append(snapshot())
                                  .toString();
    }

    private static final class DefaultHistogramSnapshot extends DropwizardHistogramSnapshot {

        static final DefaultHistogramSnapshot EMPTY = new DefaultHistogramSnapshot(
                new double[0], new double[0], 0, 0, 0, 0, 0);

        private final double[] predefinedQuantiles;
        private final double[] values;
        private final long min;
        private final long max;
        private final double mean;
        private final double median;
        private final double stdDev;

        DefaultHistogramSnapshot(double[] predefinedQuantiles, double[] values, long min, long max,
                                 double mean, double median, double stdDev) {

            this.predefinedQuantiles = predefinedQuantiles;
            this.values = values;
            this.min = min;
            this.max = max;
            this.mean = mean;
            this.median = median;
            this.stdDev = stdDev;
        }

        @Override
        public double value(double quantile) {
            for (int i = 0; i < predefinedQuantiles.length; i++) {
                if (quantile <= predefinedQuantiles[i]) {
                    return values[i];
                }
            }
            return max;
        }

        @Override
        public long[] values() {
            final long[] toReturn = new long[values.length];
            for (int i = 0; i < values.length; i++) {
                toReturn[i] = (long) values[i];
            }
            return toReturn;
        }

        @Override
        public int size() {
            return values.length;
        }

        @Override
        public double p50() {
            return median;
        }

        @Override
        public long min() {
            return min;
        }

        @Override
        public long max() {
            return max;
        }

        @Override
        public double mean() {
            return mean;
        }

        @Override
        public double stdDev() {
            return stdDev;
        }
    }
}
