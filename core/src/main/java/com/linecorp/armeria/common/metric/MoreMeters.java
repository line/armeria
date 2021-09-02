/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.metric;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.annotation.Nullable;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.DistributionSummary.Builder;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;

/**
 * Provides utilities for accessing {@link MeterRegistry}.
 */
public final class MoreMeters {

    private static final double[] PERCENTILES = { 0, 0.5, 0.75, 0.9, 0.95, 0.98, 0.99, 0.999, 1.0 };

    private static final boolean MICROMETER_1_5;

    static {
        MICROMETER_1_5 = Stream.of(Builder.class.getMethods())
                               .anyMatch(method -> method != null &&
                                                   "serviceLevelObjectives".equals(method.getName()));
    }

    /**
     * Export the percentile values only by default. We specify all properties so that we get consistent values
     * even if Micrometer changes its defaults. Most notably, we changed {@code percentilePrecision},
     * {@code expiry} and {@code bufferLength} due to the following reasons:
     * <ul>
     *   <li>The default {@code percentilePrecision} of 1 is way too inaccurate.</li>
     *   <li>Histogram buckets should be rotated every minute rather than every some-arbitrary-seconds
     *       because that fits better to human's mental model of time. Micrometer's 2 minutes / 3 buffers
     *       (i.e. rotate every 40 seconds) does not make much sense.</li>
     * </ul>
     */
    private static volatile DistributionStatisticConfig distStatCfg =
            DistributionStatisticConfig.builder()
                                       .percentilesHistogram(false)
                                       .sla()
                                       .percentiles(PERCENTILES)
                                       .percentilePrecision(2)
                                       .minimumExpectedValue(1L)
                                       .maximumExpectedValue(Long.MAX_VALUE)
                                       .expiry(Duration.ofMinutes(3))
                                       .bufferLength(3)
                                       .build();

    /**
     * Sets the {@link DistributionStatisticConfig} to use when the factory methods in {@link MoreMeters} create
     * a {@link Timer} or a {@link DistributionSummary}.
     */
    public static void setDistributionStatisticConfig(DistributionStatisticConfig config) {
        requireNonNull(config, "config");
        distStatCfg = config;
    }

    /**
     * Returns the {@link DistributionStatisticConfig} to use when the factory methods in {@link MoreMeters}
     * create a {@link Timer} or a {@link DistributionSummary}.
     */
    public static DistributionStatisticConfig distributionStatisticConfig() {
        return distStatCfg;
    }

    /**
     * Returns a newly-registered {@link DistributionSummary} configured by
     * {@link #distributionStatisticConfig()}.
     */
    public static DistributionSummary newDistributionSummary(MeterRegistry registry,
                                                             String name, Iterable<Tag> tags) {
        requireNonNull(registry, "registry");
        requireNonNull(name, "name");
        requireNonNull(tags, "tags");

        final Builder builder =
                DistributionSummary.builder(name)
                                   .tags(tags)
                                   .publishPercentiles(distStatCfg.getPercentiles())
                                   .publishPercentileHistogram(distStatCfg.isPercentileHistogram())
                                   .distributionStatisticBufferLength(distStatCfg.getBufferLength())
                                   .distributionStatisticExpiry(distStatCfg.getExpiry());

        if (MICROMETER_1_5) {
            builder.maximumExpectedValue(distStatCfg.getMaximumExpectedValueAsDouble())
                   .minimumExpectedValue(distStatCfg.getMinimumExpectedValueAsDouble())
                   .serviceLevelObjectives(distStatCfg.getServiceLevelObjectiveBoundaries());
        } else {
            @Nullable
            final Double maxExpectedValueNanos = distStatCfg.getMaximumExpectedValueAsDouble();
            @Nullable
            final Double minExpectedValueNanos = distStatCfg.getMinimumExpectedValueAsDouble();
            @Nullable
            final Long maxExpectedValue =
                    maxExpectedValueNanos != null ? maxExpectedValueNanos.longValue() : null;
            @Nullable
            final Long minExpectedValue =
                    minExpectedValueNanos != null ? minExpectedValueNanos.longValue() : null;
            builder.maximumExpectedValue(maxExpectedValue);
            builder.minimumExpectedValue(minExpectedValue);
            @Nullable
            final double[] slas = distStatCfg.getServiceLevelObjectiveBoundaries();
            if (slas != null) {
                builder.sla(Arrays.stream(slas).mapToLong(sla -> (long) sla).toArray());
            }
        }
        return builder.register(registry);
    }

    /**
     * Returns a newly-registered {@link Timer} configured by {@link #distributionStatisticConfig()}.
     */
    public static Timer newTimer(MeterRegistry registry, String name, Iterable<Tag> tags) {
        requireNonNull(registry, "registry");
        requireNonNull(name, "name");
        requireNonNull(tags, "tags");

        @Nullable
        final Double maxExpectedValueNanos = distStatCfg.getMaximumExpectedValueAsDouble();
        @Nullable
        final Double minExpectedValueNanos = distStatCfg.getMinimumExpectedValueAsDouble();
        @Nullable
        final Duration maxExpectedValue =
                maxExpectedValueNanos != null ? Duration.ofNanos(maxExpectedValueNanos.longValue()) : null;
        @Nullable
        final Duration minExpectedValue =
                minExpectedValueNanos != null ? Duration.ofNanos(minExpectedValueNanos.longValue()) : null;

        return Timer.builder(name)
                    .tags(tags)
                    .maximumExpectedValue(maxExpectedValue)
                    .minimumExpectedValue(minExpectedValue)
                    .publishPercentiles(distStatCfg.getPercentiles())
                    .publishPercentileHistogram(distStatCfg.isPercentileHistogram())
                    .distributionStatisticBufferLength(distStatCfg.getBufferLength())
                    .distributionStatisticExpiry(distStatCfg.getExpiry())
                    .register(registry);
    }

    /**
     * Returns a newly-created immutable {@link Map} which contains all values of {@link Meter}s in the
     * specified {@link MeterRegistry}. The format of the key string is:
     * <ul>
     *   <li>{@code <name>#<statistic>{tagName=tagValue,...}}</li>
     *   <li>e.g. {@code "armeria.server.active.requests#value{method=greet}"}</li>
     *   <li>e.g. {@code "some.subsystem.some.value#count"} (no tags)</li>
     * </ul>
     * Note: It is not recommended to use this method for the purposes other than testing.
     */
    public static Map<String, Double> measureAll(MeterRegistry registry) {
        requireNonNull(registry, "registry");

        final ImmutableMap.Builder<String, Double> builder = ImmutableMap.builder();

        registry.forEachMeter(meter -> Streams.stream(meter.measure()).forEach(measurement -> {
            final String fullName = measurementName(meter.getId(), measurement);
            final double value = measurement.getValue();
            builder.put(fullName, value);
        }));

        return builder.build();
    }

    private static String measurementName(Meter.Id id, Measurement measurement) {
        final StringBuilder buf = new StringBuilder();

        // Append name.
        buf.append(id.getName());

        // Append statistic.
        buf.append('#');
        buf.append(measurement.getStatistic().getTagValueRepresentation());

        // Append tags if there are any.
        final Iterator<Tag> tagsIterator = id.getTags().iterator();
        if (tagsIterator.hasNext()) {
            buf.append('{');
            tagsIterator.forEachRemaining(tag -> buf.append(tag.getKey()).append('=')
                                                    .append(tag.getValue()).append(','));
            buf.setCharAt(buf.length() - 1, '}');
        }
        return buf.toString();
    }

    private MoreMeters() {}
}
