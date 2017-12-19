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

import java.util.Iterator;
import java.util.Map;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

/**
 * Provides utilities for accessing {@link MeterRegistry}.
 */
public final class MoreMeters {

    private static final double[] PERCENTILES = { 0, 0.5, 0.75, 0.9, 0.95, 0.98, 0.99, 0.999, 1.0 };

    /**
     * Returns a newly-registered {@link DistributionSummary} with percentile publication configured.
     */
    public static DistributionSummary summaryWithDefaultQuantiles(MeterRegistry registry,
                                                                  String name, Iterable<Tag> tags) {
        requireNonNull(registry, "registry");
        requireNonNull(name, "name");
        requireNonNull(tags, "tags");
        return DistributionSummary.builder(name)
                                  .tags(tags)
                                  .publishPercentiles(PERCENTILES)
                                  .register(registry);
    }

    /**
     * Returns a newly-registered {@link Timer} with percentile publication configured.
     */
    public static Timer timerWithDefaultQuantiles(MeterRegistry registry, String name, Iterable<Tag> tags) {
        requireNonNull(registry, "registry");
        requireNonNull(name, "name");
        requireNonNull(tags, "tags");
        return Timer.builder(name)
                    .tags(tags)
                    .publishPercentiles(PERCENTILES)
                    .register(registry);
    }

    /**
     * Returns a newly-created immutable {@link Map} which contains all values of {@link Meter}s in the
     * specified {@link MeterRegistry}. The format of the key string is:
     * <ul>
     *   <li>{@code <name>#<statistic>{tagName=tagValue,...}}</li>
     *   <li>e.g. {@code "armeria.server.activeRequests#value{method=greet}"}</li>
     *   <li>e.g. {@code "someSubsystem.someValue#sumOfSquares"} (no tags)</li>
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
        buf.append(CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, measurement.getStatistic().name()));

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
