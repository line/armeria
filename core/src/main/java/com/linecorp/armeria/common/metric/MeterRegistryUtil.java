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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.prometheus.PrometheusMeterRegistry;
import io.micrometer.core.instrument.stats.quantile.Quantiles;
import io.micrometer.core.instrument.stats.quantile.WindowSketchQuantiles;
import io.micrometer.core.instrument.util.MeterId;

/**
 * Provides utilities for accessing {@link MeterRegistry}.
 */
public final class MeterRegistryUtil {

    private static final Pattern PROMETHEUS_SANITIZE_PREFIX_PATTERN = Pattern.compile("^[^a-zA-Z_]");
    private static final Pattern PROMETHEUS_SANITIZE_BODY_PATTERN = Pattern.compile("[^a-zA-Z0-9_]");
    private static final Pattern ALPHANUM_ONLY_PATTERN = Pattern.compile("^[a-zA-Z0-9]+$");

    private static final double[] DEFAULT_QUANTILES = { 0.5, 0.75, 0.95, 0.98, 0.99, 0.999, 1.0 };

    /**
     * Returns a newly-created {@link Meter} name.
     */
    public static String name(MeterRegistry registry, String... name) {
        return name(registry, MeterUnit.NONE, name);
    }

    /**
     * Returns a newly-created {@link Meter} name.
     */
    public static String name(MeterRegistry registry, Iterable<String> name) {
        return name(registry, MeterUnit.NONE, name);
    }

    /**
     * Returns a newly-created {@link Meter} name by concatenating the name of the specified {@link MeterId}
     * and the specified name parts.
     */
    public static String name(MeterRegistry registry, MeterUnit unit, MeterId id, String... name) {
        return name(registry, unit, id, ImmutableList.copyOf(requireNonNull(name, "name")));
    }

    /**
     * Returns a newly-created {@link Meter} name by concatenating the name of the specified {@link MeterId}
     * and the specified name parts.
     */
    public static String name(MeterRegistry registry, MeterUnit unit, MeterId id, Iterable<String> name) {
        return name(registry, unit, ImmutableList.<String>builder().add(id.getName()).addAll(name).build());
    }

    /**
     * Returns a newly-created {@link Meter} name.
     */
    public static String name(MeterRegistry registry, MeterUnit unit, String... name) {
        return name(registry, unit, ImmutableList.copyOf(name));
    }

    private static String name(MeterRegistry registry, MeterUnit unit, Iterable<String> name) {
        if (registry instanceof PrometheusMeterRegistry) {
            final StringBuilder buf = new StringBuilder();
            for (String n : name) {
                if (ALPHANUM_ONLY_PATTERN.matcher(n).matches()) {
                    buf.append(CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, n));
                } else {
                    buf.append(n);
                }
                buf.append('_');
            }
            buf.setLength(buf.length() - 1);
            final String unitName = unit.baseUnitName();
            if (!unitName.isEmpty()) {
                buf.append('_').append(unitName);
            }
            if (unit.isTotal()) {
                buf.append("_total");
            }

            return PROMETHEUS_SANITIZE_BODY_PATTERN.matcher(
                    PROMETHEUS_SANITIZE_PREFIX_PATTERN.matcher(buf.toString())
                                                      .replaceFirst("_")).replaceAll("_");
        }

        return String.join(".", name);
    }

    /**
     * Concatenates the {@link Tag}s of the specified {@link MeterId} and the specified {@code keyValues}.
     */
    public static Iterable<Tag> tags(MeterId id, String... keyValues) {
        return tags(id.getTags(), keyValues);
    }

    /**
     * Concatenates the specified {@link Tag}s and {@code keyValues}.
     */
    public static Iterable<Tag> tags(Iterable<Tag> tags, String... keyValues) {
        return Iterables.concat(tags, Tags.zip(keyValues));
    }

    /**
     * Returns the value of the {@link Measurement} collected from the matching {@link Meter}.
     *
     * @throws NoSuchElementException if there's no such {@link Meter} in the specified {@link MeterRegistry}
     */
    public static double measure(MeterRegistry registry, String name, String... keyValues) {
        requireNonNull(name, "name");
        requireNonNull(keyValues, "keyValues");
        return measure(registry, new MeterId(name, Tags.zip(keyValues)));
    }

    /**
     * Returns the value of the {@link Measurement} collected from the matching {@link Meter}.
     *
     * @throws NoSuchElementException if there's no such {@link Meter} in the specified {@link MeterRegistry}
     */
    public static double measure(MeterRegistry registry, String name, Iterable<Tag> tags) {
        requireNonNull(name, "name");
        requireNonNull(tags, "tags");
        return measure(registry, new MeterId(name, tags));
    }

    /**
     * Returns the value of the {@link Measurement} collected from the matching {@link Meter}.
     *
     * @throws NoSuchElementException if there's no such {@link Meter} in the specified {@link MeterRegistry}
     */
    public static double measure(MeterRegistry registry, MeterId id) {
        final List<Measurement> measurements = findMeter(registry, id).measure();
        checkState(measurements.size() == 1, "must have a single measurement: %s = %s", id, measurements);
        return measurements.get(0).getValue();
    }

    /**
     * Returns the values of the {@link Measurement}s collected from the matching {@link Meter}.
     *
     * @throws NoSuchElementException if there's no such {@link Meter} in the specified {@link MeterRegistry}
     */
    public static Map<String, Double> measureAll(MeterRegistry registry, String name, String... keyValues) {
        requireNonNull(name, "name");
        requireNonNull(keyValues, "keyValues");
        return measureAll(registry, new MeterId(name, Tags.zip(keyValues)));
    }

    /**
     * Returns the values of the {@link Measurement}s collected from the matching {@link Meter}.
     *
     * @throws NoSuchElementException if there's no such {@link Meter} in the specified {@link MeterRegistry}
     */
    public static Map<String, Double> measureAll(MeterRegistry registry, String name, Iterable<Tag> tags) {
        requireNonNull(name, "name");
        requireNonNull(tags, "tags");
        return measureAll(registry, new MeterId(name, tags));
    }

    /**
     * Returns the values of the {@link Measurement}s collected from the matching {@link Meter}.
     *
     * @throws NoSuchElementException if there's no such {@link Meter} in the specified {@link MeterRegistry}
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Double> measureAll(MeterRegistry registry, MeterId id) {
        final Meter meter = findMeter(registry, id);
        return meter.measure().stream().collect(toImmutableMap(
                m -> simplifyMeasurementName(id, m),
                Measurement::getValue
        ));
    }

    private static Meter findMeter(MeterRegistry registry, MeterId id) {
        requireNonNull(registry, "registry");
        requireNonNull(id, "id");
        return registry.getMeters().stream()
                                    .filter(m -> m.getName().equals(id.getName()) &&
                                                 Iterables.elementsEqual(m.getTags(), id.getTags()))
                                    .findAny().get();
    }

    private static String simplifyMeasurementName(MeterId id, Measurement m) {
        final StringBuilder buf = new StringBuilder();
        if (m.getName().startsWith(id.getName() + '.') ||
            m.getName().startsWith(id.getName() + '_')) {
            buf.append(m.getName().substring(id.getName().length() + 1));
        } else if (!m.getName().equals(id.getName())) {
            buf.append(m.getName());
        }

        final Set<Tag> tags = new TreeSet<>(comparing(Tag::getKey));
        tags.addAll(m.getTags());
        tags.removeAll(id.getTags());
        if (!tags.isEmpty()) {
            buf.append('{');
            tags.forEach(t -> buf.append(t.getKey()).append('=')
                                 .append(t.getValue()).append(','));
            buf.setCharAt(buf.length() - 1, '}');
        }

        return buf.toString();
    }

    /**
     * Returns whether the specified {@link MeterRegistry} implementation prefers base unit for values.
     */
    public static boolean prefersBaseUnit(MeterRegistry registry) {
        return registry instanceof PrometheusMeterRegistry;
    }

    /**
     * Returns a {@link DistributionSummary} with the default {@link Quantiles} configured.
     */
    public static DistributionSummary summaryWithDefaultQuantiles(
            MeterRegistry registry, String name, Iterable<Tag> tags) {
        return registry.summaryBuilder(name).tags(tags).quantiles(defaultQuantiles()).create();
    }

    /**
     * Returns a {@link Timer} with the default {@link Quantiles} configured.
     */
    public static Timer timerWithDefaultQuantiles(
            MeterRegistry registry, String name, Iterable<Tag> tags) {
        return registry.timerBuilder(name).tags(tags).quantiles(defaultQuantiles()).create();
    }

    private static Quantiles defaultQuantiles() {
        // According to Jon Schneider of Micrometer:
        // (1) Frugal2U is by far the fastest, but can take a while to converge.
        // (2) CKMS is slower but isn’t a successive approximation approach.
        // (3) Window Sketch is the slowest, but the quantile is sensitive to recent samples.
        return new WindowSketchQuantiles.Builder().quantiles(DEFAULT_QUANTILES).create();
    }

    private MeterRegistryUtil() {}
}
