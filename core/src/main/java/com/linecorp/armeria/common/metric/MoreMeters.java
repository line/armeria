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

package com.linecorp.armeria.common.metric;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Streams.stream;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableMap;

import io.micrometer.core.instrument.AbstractMeterRegistry;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.stats.quantile.Quantiles;
import io.micrometer.core.instrument.stats.quantile.WindowSketchQuantiles;

/**
 * Provides utilities for accessing {@link MeterRegistry}.
 */
public final class MoreMeters {

    private static final double[] DEFAULT_QUANTILES = { 0.5, 0.75, 0.95, 0.98, 0.99, 0.999, 1.0 };

    /**
     * Returns a {@link DistributionSummary} with the default {@link Quantiles} configured.
     */
    public static DistributionSummary summaryWithDefaultQuantiles(MeterRegistry registry, MeterId id) {
        requireNonNull(registry, "registry");
        requireNonNull(id, "id");
        return registry.summaryBuilder(id.name()).tags(id.tags()).quantiles(defaultQuantiles()).create();
    }

    /**
     * Returns a {@link Timer} with the default {@link Quantiles} configured.
     */
    public static Timer timerWithDefaultQuantiles(MeterRegistry registry, MeterId id) {
        requireNonNull(registry, "registry");
        requireNonNull(id, "id");
        return registry.timerBuilder(id.name()).tags(id.tags()).quantiles(defaultQuantiles()).create();
    }

    private static Quantiles defaultQuantiles() {
        // According to Jon Schneider of Micrometer:
        // (1) Frugal2U is by far the fastest, but can take a while to converge.
        // (2) CKMS is slower but isn’t a successive approximation approach.
        // (3) Window Sketch is the slowest, but the quantile is sensitive to recent samples.
        return new WindowSketchQuantiles.Builder().quantiles(DEFAULT_QUANTILES).create();
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
        checkArgument(registry instanceof AbstractMeterRegistry,
                      "registry: %s (expected: %s)", registry, AbstractMeterRegistry.class);

        final ImmutableMap.Builder<String, Double> builder = ImmutableMap.builder();
        getMeterMap(registry).forEach((id, meter) -> stream(meter.measure()).forEach(measurement -> {
            final String fullName = measurementName(id, measurement);
            final double value = measurement.getValue();
            builder.put(fullName, value);
        }));

        return builder.build();
    }

    private static String measurementName(Object id, Measurement measurement) {
        final StringBuilder buf = new StringBuilder();

        // Append name.
        final String name;
        name = getName(id);
        buf.append(name);

        // Append statistic.
        buf.append('#');
        buf.append(CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, measurement.getStatistic().name()));

        // Append tags if there are any.
        final Iterator<Tag> tagsIterator = getTags(id).iterator();
        if (tagsIterator.hasNext()) {
            buf.append('{');
            tagsIterator.forEachRemaining(tag -> buf.append(tag.getKey()).append('=')
                                                    .append(tag.getValue()).append(','));
            buf.setCharAt(buf.length() - 1, '}');
        }
        return buf.toString();
    }

    /**
     * Micrometer, as of 0.10.0, has a bug where Meter.getName() sometimes returns conventional name
     * and sometimes not. We use reflection to get the consistent meter names as a workaround.
     */
    @SuppressWarnings("unchecked")
    private static Map<Object, Meter> getMeterMap(MeterRegistry registry) {
        try {
            @SuppressWarnings("JavaReflectionMemberAccess")
            final Field field = AbstractMeterRegistry.class.getDeclaredField("meterMap");
            field.setAccessible(true);
            return (Map<Object, Meter>) field.get(registry);
        } catch (Exception e) {
            throw new IllegalStateException("failed to retrieve the meter map", e);
        }
    }

    private static String getName(Object id) {
        checkMeterIdType(id);
        try {
            final Method getName = id.getClass().getDeclaredMethod("getName");
            getName.setAccessible(true);
            return String.valueOf(getName.invoke(id));
        } catch (Exception e) {
            throw new IllegalStateException("failed to get the meter name", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Iterable<Tag> getTags(Object id) {
        checkMeterIdType(id);
        try {
            final Method getTags = id.getClass().getDeclaredMethod("getTags");
            getTags.setAccessible(true);
            return (Iterable<Tag>) getTags.invoke(id);
        } catch (Exception e) {
            throw new IllegalStateException("failed to get the meter tags", e);
        }
    }

    private static void checkMeterIdType(Object id) {
        final Class<?> idType = id.getClass();
        checkState("io.micrometer.core.instrument.AbstractMeterRegistry$MeterId".equals(idType.getName()),
                   "unexpected meter id type: %s", idType);
    }

    private MoreMeters() {}
}
