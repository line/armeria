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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A registry of {@link Metric}s and {@link MetricGroup}s.
 */
public class Metrics {

    private static final Logger logger = LoggerFactory.getLogger(Metrics.class);

    private final Map<MetricKey, Metric> metricMap = new ConcurrentHashMap<>();
    private final Map<MetricKey, MetricGroup> metricGroups = new ConcurrentHashMap<>();
    private final List<MetricsExporter> exporters = new ArrayList<>();

    /**
     * Adds the specified {@link MetricsExporter} to this registry. The {@link MetricsExporter} will be
     * notified with all existing and future {@link Metric}s added to this registry. This method has no effect
     * to add the same {@link MetricsExporter} more than once.
     */
    public final void addExporter(MetricsExporter exporter) {
        synchronized (exporters) {
            if (exporters.stream().noneMatch(e -> e == exporter)) {
                exporters.add(exporter);

                // Export all existing metrics.
                metrics().forEach(m -> export(exporter, m));
            }
        }
    }

    /**
     * Removes the specified {@link MetricsExporter} from this registry. If removed, the {@link MetricsExporter}
     * will no longer get notified when a new {@link Metric} is registered. This method has no effect if the
     * {@link MetricsExporter} was not added before.
     */
    public final void removeExporter(MetricsExporter exporter) {
        synchronized (exporters) {
            for (Iterator<MetricsExporter> i = exporters.iterator(); i.hasNext();) {
                final MetricsExporter e = i.next();
                if (e == exporter) {
                    i.remove();
                    break;
                }
            }
        }
    }

    /**
     * Registers a {@link Metric} into this registry. This method has no effect if the specified {@link Metric}
     * has been registered already.
     *
     * @param metric the {@link Metric} to register.
     *
     * @return {@code metric}
     * @throws IllegalStateException if there is already a {@link Metric} registered for the same
     *                               {@link MetricKey} and it is not the same instance with {@code metric}
     */
    public final <T extends Metric> T register(T metric) {
        requireNonNull(metric, "metric");

        @SuppressWarnings("unchecked")
        final Class<T> type = (Class<T>) metric.getClass();
        final T actualMetric = metric(metric.key(), metric.unit(), metric.description(),
                                      type, (k, u, d) -> metric);
        if (actualMetric != metric) {
            throw new IllegalStateException("A metric has been registered already for key: " + metric.key());
        }

        return metric;
    }

    /**
     * Registers a {@link MetricGroup} into this registry. This method has no effect if the specified
     * {@link MetricGroup} has been registered already.
     *
     * @param group the {@link MetricGroup} to register.
     *
     * @return {@code group}
     * @throws IllegalStateException if there is already a {@link MetricGroup} registered for the same
     *                               {@link MetricKey} and it is not the same instance with {@code group}
     */
    public final <T extends MetricGroup> T register(T group) {
        requireNonNull(group, "group");

        @SuppressWarnings("unchecked")
        final Class<T> type = (Class<T>) group.getClass();
        final T actualGroup = group(group.key(), type, (metrics, key) -> group);
        if (actualGroup != group) {
            throw new IllegalStateException("A group has been registered already for key: " + group.key());
        }

        return group;
    }

    /**
     * Returns the {@link Metric} with the specified key and type.
     *
     * @return the {@link Metric} if found. {@code null} otherwise.
     *
     * @throws IllegalStateException if a {@link Metric} with the specified key exists but its type mismatches
     *                               the specified one.
     */
    @Nullable
    public final <T extends Metric> T metric(MetricKey key, Class<T> type) {
        requireNonNull(key, "key");
        requireNonNull(type, "type");

        final Metric metric = metricMap.get(key);
        if (metric == null) {
            return null;
        }

        if (!type.isInstance(metric)) {
            throw new IllegalStateException("A metric exists but its type mismatches: " + key);
        }

        @SuppressWarnings("unchecked")
        final T castMetric = (T) metric;
        return castMetric;
    }

    /**
     * Registers a {@link Metric} into this registry or retrieves an existing one.
     *
     * @param key the {@link MetricKey} of the {@link Metric}
     * @param unit the {@link MetricUnit} of the {@link Metric}
     * @param description the human-readable description of the {@link Metric}
     * @param type the type of the {@link Metric} produced by {@code factory}
     * @param factory a factory that creates a {@link Metric} of {@code type}
     *
     * @return the {@link Metric} of {@code type}, which may or may not be created by
     *         {@code factory}
     * @throws IllegalStateException if there is already a {@link Metric} of different class or
     *                               {@link MetricUnit} registered for the same {@link MetricKey}
     */
    public final <T extends Metric> T metric(MetricKey key, MetricUnit unit, String description,
                                             Class<T> type, MetricFactory<T> factory) {
        requireNonNull(key, "key");
        requireNonNull(unit, "unit");
        requireNonNull(description, "description");
        requireNonNull(type, "type");
        requireNonNull(factory, "factory");

        final Metric metric = metricMap.computeIfAbsent(key, k -> {
            final Metric newMetric;
            try {
                newMetric = factory.create(key, unit, description);
            } catch (Exception e) {
                throw new IllegalStateException("failed to create a new metric: " + key, e);
            }

            // Ensure the factory created the metric correctly.
            if (!type.isInstance(newMetric)) {
                throw new IllegalStateException(
                        "factory created a metric with incompatible type for key: " + key +
                        " (expected: " + type.getName() +
                        ", actual: " + newMetric.getClass().getName() + ')');
            }
            if (!k.equals(newMetric.key())) {
                throw new IllegalStateException(
                        "factory created a metric with mismatching key for key: " + key +
                        " (actual: " + newMetric.key() + ')');
            }
            if (unit != newMetric.unit()) {
                throw new IllegalStateException(
                        "factory created a metric with mismatching unit for key: " + key +
                        " (expected: " + unit + ", actual: " + newMetric.unit() + ')');
            }

            // Export the new metric.
            synchronized (exporters) {
                exporters.forEach(exporter -> export(exporter, newMetric));
            }

            return newMetric;
        });

        if (!type.isInstance(metric)) {
            throw new IllegalStateException(
                    "A metric of different type has been registered already for key: " + key +
                    " (expected: " + type.getName() +
                    ", actual: " + metric.getClass().getName() + ')');
        }
        if (unit != metric.unit()) {
            throw new IllegalStateException(
                    "A metric of different unit has been registered already for key: " + key +
                    " (expected: " + unit + ", actual: " + metric.unit() + ')');
        }

        @SuppressWarnings("unchecked")
        final T castMetric = (T) metric;
        return castMetric;
    }

    private static void export(MetricsExporter exporter, Metric metric) {
        try {
            exporter.export(metric);
        } catch (Exception e) {
            logger.warn("Unexpected exception while exporting a metric: {} ({})",
                        exporter, metric, e);
        }
    }

    /**
     * Returns the immutable {@link Collection} of {@link Metric}s in this registry.
     */
    public final Collection<Metric> metrics() {
        return Collections.unmodifiableCollection(metricMap.values());
    }

    /**
     * Returns the immutable {@link Collection} of {@link Metric}s in this registry whose name starts with
     * the name of the prefix and whose labels contain all labels of the prefix. For example, with the
     * following metric keys:
     * <ul>
     *   <li>{@code "a.b{foo=1}"}</li>
     *   <li>{@code "a.c{foo=1,bar=2}"}</li>
     * </ul>
     * you will observe that:
     * <ul>
     *   <li>prefix {@code "a"} will match both keys.</li>
     *   <li>prefix {@code "a.b"} will match the first key.</li>
     *   <li>prefix {@code "a{foo=1}"} will match both keys.</li>
     *   <li>prefix {@code "a{bar=2}"} will match the second key.</li>
     * </ul>
     *
     * @see MetricKey#includes(MetricKey)
     */
    public final Collection<Metric> metrics(MetricKey prefix) {
        requireNonNull(prefix, "prefix");
        return metricMap.values().stream()
                        .filter(m -> prefix.includes(m.key()))
                        .collect(toImmutableList());
    }

    /**
     * Returns the {@link MetricGroup} with the specified key and type.
     *
     * @return the {@link MetricGroup} if found. {@code null} otherwise.
     *
     * @throws IllegalStateException if a {@link MetricGroup} with the specified key exists but its type
     *                               mismatches the specified one.
     */
    @Nullable
    public final <T extends MetricGroup> T group(MetricKey key, Class<T> type) {
        requireNonNull(key, "key");
        requireNonNull(type, "type");

        final MetricGroup group = metricGroups.get(key);
        if (group == null) {
            return null;
        }

        if (!type.isInstance(group)) {
            throw new IllegalStateException("metric group exists but its type mismatches: " + key);
        }

        @SuppressWarnings("unchecked")
        final T castGroup = (T) group;
        return castGroup;
    }

    /**
     * Registers a {@link MetricGroup} into this registry or retrieves an existing one.
     *
     * @param key the {@link MetricKey} of the {@link MetricGroup}
     * @param type the type of the {@link MetricGroup} produced by {@code factory}
     * @param factory a factory that creates a {@link MetricGroup} of {@code type}
     *
     * @return the {@link MetricGroup} of {@code type}, which may or may not be created by
     *         {@code factory}
     * @throws IllegalStateException if there is already a {@link MetricGroup} of different class
     *                               registered for the same {@link MetricKey}
     */
    public final <T extends MetricGroup> T group(MetricKey key, Class<T> type,
                                                 BiFunction<Metrics, MetricKey, T> factory) {
        requireNonNull(key, "key");
        requireNonNull(type, "type");
        requireNonNull(factory, "factory");

        final MetricGroup group = metricGroups.computeIfAbsent(key, k -> factory.apply(this, k));
        if (!type.isInstance(group)) {
            throw new IllegalStateException(
                    "A metric group of different type has been registered already for key: " + key +
                    " (expected: " + type.getName() +
                    ", actual: " + group.getClass().getName() + ')');
        }

        @SuppressWarnings("unchecked")
        final T castGroup = (T) group;
        return castGroup;
    }

    /**
     * Returns the immutable {@link Collection} of {@link MetricGroup}s in this registry.
     */
    public final Collection<MetricGroup> groups() {
        return Collections.unmodifiableCollection(metricGroups.values());
    }

    /**
     * Returns the immutable {@link Collection} of {@link MetricGroup}s in this registry.
     *
     * @param type the type of {@link MetricGroup}
     */
    public final <T extends MetricGroup> Collection<T> groups(Class<T> type) {
        return groupStream(type).collect(toImmutableList());
    }

    /**
     * Returns the immutable {@link Collection} of {@link MetricGroup}s in this registry whose name starts with
     * the name of the prefix and whose labels contain all labels of the prefix. For example, with the
     * following metric keys:
     * <ul>
     *   <li>{@code "a.b{foo=1}"}</li>
     *   <li>{@code "a.c{foo=1,bar=2}"}</li>
     * </ul>
     * you will observe that:
     * <ul>
     *   <li>prefix {@code "a"} will match both keys.</li>
     *   <li>prefix {@code "a.b"} will match the first key.</li>
     *   <li>prefix {@code "a{foo=1}"} will match both keys.</li>
     *   <li>prefix {@code "a{bar=2}"} will match the second key.</li>
     * </ul>
     *
     * @param type the type of {@link MetricGroup}
     *
     * @see MetricKey#includes(MetricKey)
     */
    public final <T extends MetricGroup> Collection<T> groups(MetricKey prefix, Class<T> type) {
        return groupStream(type).filter(g -> prefix.includes(g.key())).collect(toImmutableList());
    }

    private <T extends MetricGroup> Stream<T> groupStream(Class<T> type) {
        requireNonNull(type, "type");
        return metricGroups.values().stream()
                           .filter(type::isInstance)
                           .map(type::cast);
    }
}
