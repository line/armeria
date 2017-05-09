/**
 * Copyright 2017 LINE Corporation
 * <p>
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.metric;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import com.linecorp.armeria.internal.metric.PrometheusMetricRequestDecorator;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;

/**
 * Subclass {@link CollectorRegistry} that allows registering metrics with the same name.
 */
public class PrometheusRegistry extends CollectorRegistry {
    private final ConcurrentMap<String, ? extends Collector> nameToCollector;
    private final Map<String, ? extends Collector> nameToCollectorView;

    /**
     * Creates a new instance.
     */
    public PrometheusRegistry() {
        nameToCollector = new ConcurrentHashMap<>();
        nameToCollectorView = Collections.unmodifiableMap(nameToCollector);
    }

    /**
     * Registers a {@link Collector} conditionally.
     * Registering the same name in base {@link CollectorRegistry} will throw an exception.
     * So this extended registry provides if-not-exists function.
     * Allows use in multiple instances of {@link PrometheusMetricRequestDecorator}.
     * @param name collector name
     * @param createCollector function to generate a collector if absent
     * @param <T> type of {@link Collector}
     * @return existing or newly created collector.
     */
    public synchronized <T extends Collector> T register(String name, Function<String, T> createCollector) {
        @SuppressWarnings("unchecked")
        ConcurrentMap<String, T> map = (ConcurrentMap<String, T>) nameToCollector;
        Function<T, T> register = collector -> {
            super.register(collector);
            return collector;
        };
        return map.computeIfAbsent(name, register.compose(createCollector));
    }

    @Override
    public synchronized void unregister(Collector collector) {
        if (nameToCollector.values().remove(collector)) {
            super.unregister(collector);
        }
    }

    /**
     * Unregisters a collector by name.
     * @param name name given to collector
     * @return {@code true} if exists and unregistered. {@code false} otherwise.
     */
    public synchronized boolean unregister(String name) {
        Collector collector = nameToCollector.remove(name);
        if (collector == null) {
            return false;
        }
        super.unregister(collector);
        return true;
    }

    /**
     * Immutable map of metric name to Collector.
     * @return map of name to Collector.
     */
    public Map<String, ? extends Collector> asMap() {
        return nameToCollectorView;
    }
}
