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

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Set;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricSet;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.internal.guava.stream.GuavaCollectors;

/**
 * {@link Metric}s for a {@link HealthCheckedEndpointGroup}.
 */
class EndpointHealthStateGaugeSet implements MetricSet {
    private static final String METRIC_NAME_PREFIX = "healthcheck.";
    private final HealthCheckedEndpointGroup endpointGroup;
    private final String metricName;

    EndpointHealthStateGaugeSet(HealthCheckedEndpointGroup endpointGroup,
                                String metricName) {
        this.endpointGroup = requireNonNull(endpointGroup, "endpointGroup");
        this.metricName = requireNonNull(metricName, "metricName");
    }

    @Override
    public Map<String, Metric> getMetrics() {
        return ImmutableMap.of(
                METRIC_NAME_PREFIX + metricName + ".all.server.count",
                (Gauge<Integer>) endpointGroup.allServers::size,
                METRIC_NAME_PREFIX + metricName + ".healthy.server.count",
                (Gauge<Integer>) endpointGroup.healthyEndpoints::size,
                METRIC_NAME_PREFIX + metricName + ".healthy.servers",
                (Gauge<Set<String>>) () -> ImmutableSet.copyOf(endpointGroup.healthyEndpoints)
                                                       .stream()
                                                       .map(Endpoint::authority)
                                                       .collect(GuavaCollectors.toImmutableSet()));
    }
}
