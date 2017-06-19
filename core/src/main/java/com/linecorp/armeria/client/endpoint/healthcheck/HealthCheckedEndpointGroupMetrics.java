/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.client.endpoint.healthcheck;

import static com.linecorp.armeria.common.metric.MeterRegistryUtil.name;
import static com.linecorp.armeria.common.metric.MeterRegistryUtil.tags;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.metric.MeterUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.util.MeterId;

/**
 * {@link MeterBinder} for a {@link HealthCheckedEndpointGroup}.
 */
class HealthCheckedEndpointGroupMetrics implements MeterBinder {

    private final HealthCheckedEndpointGroup endpointGroup;
    private final MeterId id;

    HealthCheckedEndpointGroupMetrics(HealthCheckedEndpointGroup endpointGroup, MeterId id) {
        this.endpointGroup = requireNonNull(endpointGroup, "endpointGroup");
        this.id = requireNonNull(id, "id");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        final String count = name(registry, MeterUnit.NONE, id, "count");
        registry.gauge(count, tags(id, "state", "healthy"), endpointGroup,
                       unused -> endpointGroup.endpoints().size());
        registry.gauge(count, tags(id, "state", "unhealthy"), endpointGroup,
                       unused -> endpointGroup.allServers.size() - endpointGroup.endpoints().size());

        final ListenerImpl listener = new ListenerImpl(
                registry, name(registry, MeterUnit.NONE, id, "healthy"), id.getTags());
        listener.accept(endpointGroup.endpoints());
        endpointGroup.addListener(listener);
    }

    private final class ListenerImpl implements Consumer<List<Endpoint>> {

        private final MeterRegistry registry;
        private final String name;
        private final Iterable<Tag> tags;
        private final ConcurrentMap<String, Boolean> healthMap = new ConcurrentHashMap<>();

        ListenerImpl(MeterRegistry registry, String name, Iterable<Tag> tags) {
            this.registry = registry;
            this.name = name;
            this.tags = tags;
        }

        @Override
        public void accept(List<Endpoint> endpoints) {
            final Map<String, Boolean> endpointsToUpdate = new HashMap<>();
            endpoints.forEach(e -> endpointsToUpdate.put(e.authority(), true));
            endpointGroup.allServers.forEach(
                    conn -> endpointsToUpdate.putIfAbsent(conn.endpoint().authority(), false));

            // Update the previously appeared endpoints.
            healthMap.entrySet().forEach(e -> {
                final String authority = e.getKey();
                final Boolean healthy = endpointsToUpdate.remove(authority);
                if (healthy != null) {
                    e.setValue(healthy);
                }
            });

            // Process the newly appeared endpoints.
            endpointsToUpdate.forEach((authority, healthy) -> {
                healthMap.put(authority, healthy);
                registry.gauge(name, tags(tags, "authority", authority),
                               this, unused -> healthMap.get(authority) ? 1 : 0);
            });
        }
    }
}
