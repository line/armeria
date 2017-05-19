/**
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.logging;

import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.CollectorRegistry;

public class PrometheusRegistryWrapper {
    private CollectorRegistry collectorRegistry;
    private ConcurrentMap<String, ? extends Collector> names;

    public PrometheusRegistryWrapper() {
        collectorRegistry = new CollectorRegistry();
        names = new ConcurrentHashMap<>();
    }

    public <T extends Collector> T create(String name, Function<String, T> ifAbsent) {
        ConcurrentMap<String, T> map = (ConcurrentMap<String, T>) names;
        Function<T, T> registerIfAbsent = collector -> {
            collectorRegistry.register(collector);
            return collector;
        };
        return map.computeIfAbsent(name, registerIfAbsent.compose((ifAbsent)));
    }

    public Enumeration<MetricFamilySamples> metricFamilySamples() {
        return collectorRegistry.metricFamilySamples();
    }

}
