/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.client.endpoint.healthcheck;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * {@link MeterBinder} for a {@link HealthCheckedEndpointGroup}.
 */
final class HealthCheckedEndpointGroupMetrics implements MeterBinder {

    private final HealthCheckedEndpointGroup endpointGroup;
    private final MeterIdPrefix idPrefix;

    HealthCheckedEndpointGroupMetrics(HealthCheckedEndpointGroup endpointGroup, MeterIdPrefix idPrefix) {
        this.endpointGroup = requireNonNull(endpointGroup, "endpointGroup");
        this.idPrefix = requireNonNull(idPrefix, "idPrefix");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        final String count = idPrefix.name("count");
        registry.gauge(count, idPrefix.tags("state", "healthy"), endpointGroup,
                       unused -> endpointGroup.endpoints().size());
        registry.gauge(count, idPrefix.tags("state", "unhealthy"), endpointGroup,
                       unused -> endpointGroup.delegate.endpoints().size() - endpointGroup.endpoints().size());

        final ListenerImpl listener = new ListenerImpl(registry, idPrefix.append("healthy"));
        endpointGroup.addListener(listener, true);
    }

    private final class ListenerImpl implements Consumer<List<Endpoint>> {

        private final MeterRegistry registry;
        private final MeterIdPrefix idPrefix;
        private final ConcurrentMap<Endpoint, Boolean> healthMap = new ConcurrentHashMap<>();

        ListenerImpl(MeterRegistry registry, MeterIdPrefix idPrefix) {
            this.registry = registry;
            this.idPrefix = idPrefix;
        }

        @Override
        public void accept(List<Endpoint> endpoints) {
            final Map<Endpoint, Boolean> endpointsToUpdate = new HashMap<>();
            endpoints.forEach(e -> endpointsToUpdate.put(e, true));
            endpointGroup.delegate.endpoints().forEach(
                    e -> endpointsToUpdate.putIfAbsent(e, false));

            // Update the previously appeared endpoints.
            healthMap.entrySet().forEach(e -> {
                final Endpoint authority = e.getKey();
                final Boolean healthy = endpointsToUpdate.remove(authority);
                e.setValue(Boolean.TRUE.equals(healthy));
            });

            // Process the newly appeared endpoints.
            endpointsToUpdate.forEach((endpoint, healthy) -> {
                healthMap.put(endpoint, healthy);
                final List<Tag> tags = new ArrayList<>(2);
                tags.add(Tag.of("authority", endpoint.authority()));
                final String ipAddr = endpoint.hasIpAddr() ? endpoint.ipAddr() : "";
                assert ipAddr != null;
                tags.add(Tag.of("ip", ipAddr));
                registry.gauge(idPrefix.name(), idPrefix.tags(tags),
                               this, unused -> healthMap.get(endpoint) ? 1 : 0);
            });
        }
    }
}
