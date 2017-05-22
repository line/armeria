/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.internal.metric;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;

import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.CollectorRegistry;

/**
 * Allows reusing an existing {@link Collector} instead of raising an exception when the {@link Collector}
 * being registered has the same metric family name.
 */
final class PrometheusUtil {

    private static final Pattern METRIC_NAME_PATTERN = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");

    @VisibleForTesting
    static final Map<CollectorRegistry, Map<String, Collector>> collectors = new WeakHashMap<>();

    /**
     * Registers a {@link Collector} if not registered already.
     * Unlike {@link CollectorRegistry#register(Collector)} which throws an exception when there's a
     * {@link Collector} registered with the same metric family name, this method returns the existing
     * {@link Collector}, only if it was registered via this method.
     *
     * @param collectorRegistry the {@link CollectorRegistry} to register a {@link Collector} to
     * @param name the name of the {@link MetricFamilySamples} produced by the {@link Collector}
     *             being registered
     * @param collectorFactory the {@link Function} that creates a {@link Collector} if there is no
     *                         {@link Collector} bound to the specified {@code name} yet
     * @param <T> type of {@link Collector}
     * @return existing or newly created {@link Collector}
     * @throws IllegalArgumentException if one of the following conditions meet:
     * <ul><li>A {@link Collector} with the same metric family name was registered to the
     * {@code collectorRegistry} without going through this method. e.g. <pre>{@code
     * CollectorRegistry registry = new CollectorRegistry();
     * // Register without going through the wrapper.
     * registry.register(Counter.build("foo", "counting the number of foos"));
     * // Will fail with an IllegalArgumentException.
     * registerIfAbsent(registry, "foo", name -> Counter.build(name, "counting the number of foos"));
     * }</pre></li>
     * <li>One of the samples produced by an existing {@link Collector} has the same name. e.g. <pre>{@code
     * registerIfAbsent(registry, "foo", name -> Summary.build("foo", "the summary of foos"));
     * // Will fail because the summary above will register "foo_count".
     * registryIfAbsent(registry, "foo_count", name -> Counter.build(name, "counting the number of foos"));
     * }</pre></li></ul>
     */
    static synchronized <T extends Collector> T registerIfAbsent(
            CollectorRegistry collectorRegistry, String name, Function<String, T> collectorFactory) {

        requireNonNull(collectorRegistry, "collectorRegistry");
        requireNonNull(name, "name");
        requireNonNull(collectorFactory, "collectorFactory");

        checkArgument(METRIC_NAME_PATTERN.matcher(name).matches(),
                      "name: %s (expected: matches %s)", name, METRIC_NAME_PATTERN);

        final Map<String, Collector> map =
                collectors.computeIfAbsent(collectorRegistry, unused -> new HashMap<>());

        Collector collector = map.get(name);
        if (collector == null) {
            // Create a new Collector using the given factory.
            collector = collectorFactory.apply(name);
            requireNonNull(collector, "collectorFactory returned null.");

            // Get the metric family names of the collector and ensure the collector produced the metric family
            // with the specified name.
            final List<MetricFamilySamples> mfsList = describe(collector);
            final List<String> actualNames = mfsList.stream()
                                                    .filter(Objects::nonNull)
                                                    .map(mfs -> mfs.name)
                                                    .filter(Objects::nonNull)
                                                    .collect(toImmutableList());
            if (!actualNames.contains(name)) {
                throw new IllegalArgumentException(
                        "collectorFactory did not return a Collector that produces a metric family " +
                        "whose name is '" + name + "'.");
            }

            collectorRegistry.register(collector);
            for (String n : actualNames) {
                map.put(n, collector);
            }
        }

        @SuppressWarnings("unchecked")
        final T castCollector = (T) collector;
        return castCollector;
    }

    /**
     * Unregisters the specified {@link Collector} from the specified {@link CollectorRegistry}.
     */
    static synchronized boolean unregister(CollectorRegistry collectorRegistry, Collector collector) {
        requireNonNull(collectorRegistry, "collectorRegistry");
        requireNonNull(collector, "collector");

        final Map<String, Collector> map = collectors.get(collectorRegistry);
        if (map == null) {
            return false;
        }

        if (map.values().removeIf(c -> c == collector)) {
            collectorRegistry.unregister(collector);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Unregisters the {@link Collector} with the specified {@code name} from the specified
     * {@link CollectorRegistry}.
     */
    static synchronized boolean unregister(CollectorRegistry collectorRegistry, String name) {
        requireNonNull(collectorRegistry, "collectorRegistry");
        requireNonNull(name, "name");

        final Map<String, Collector> map = collectors.get(collectorRegistry);
        if (map == null) {
            return false;
        }

        final Collector collector = map.remove(name);
        if (collector == null) {
            return false;
        }

        // NB: A collector may have more than one binding if it provides multiple sample families.
        map.values().removeIf(c -> c == collector);
        collectorRegistry.unregister(collector);
        return true;
    }

    /**
     * Unregisters all {@link Collector}s registered to the specified {@link CollectorRegistry} via the
     * {@link #registerIfAbsent(CollectorRegistry, String, Function)} method.
     */
    static synchronized boolean unregisterAll(CollectorRegistry collectorRegistry) {
        requireNonNull(collectorRegistry, "collectorRegistry");
        final Map<String, Collector> map = collectors.remove(collectorRegistry);
        if (map == null || map.isEmpty()) {
            return false;
        }

        for (Collector collector : map.values()) {
            collectorRegistry.unregister(collector);
        }

        return true;
    }

    private static <T extends Collector> List<MetricFamilySamples> describe(T collector) {
        List<MetricFamilySamples> mfsList;
        if (collector instanceof Collector.Describable) {
            mfsList = ((Collector.Describable) collector).describe();
        } else {
            mfsList = collector.collect();
        }
        return mfsList;
    }

    private PrometheusUtil() {}
}
