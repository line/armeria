/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.client.consul;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;

import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.consul.ConsulConfigSetters;
import com.linecorp.armeria.internal.consul.ConsulClient;
import com.linecorp.armeria.internal.consul.ConsulClientBuilder;
import com.linecorp.armeria.server.consul.ConsulUpdatingListenerBuilder;

/**
 * A builder class for {@link ConsulEndpointGroup}.
 * <h3>Examples</h3>
 * <pre>{@code
 * ConsulEndpointGroup endpointGroup = ConsulEndpointGroup.builder(consulUri, "myService")
 *                                                        .build();
 * List<Endpoint> endpoints = endpointGroup.endpoints();
 * sb.serverListener(listener);
 * }</pre>
 */
public final class ConsulEndpointGroupBuilder implements ConsulConfigSetters {
    private static final long DEFAULT_HEALTH_CHECK_INTERVAL_MILLIS = 10_000;

    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();

    private final String serviceName;
    private long registryFetchIntervalMillis = DEFAULT_HEALTH_CHECK_INTERVAL_MILLIS;
    private boolean useHealthyEndpoints;
    private final ConsulClientBuilder consulClientBuilder;

    ConsulEndpointGroupBuilder(URI consulUri, String serviceName) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
        consulClientBuilder = ConsulClient.builder(consulUri);
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} of the {@link ConsulEndpointGroup}.
     */
    public ConsulEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return this;
    }

    /**
     * Sets the interval between fetching registry requests.
     * If not set, {@value #DEFAULT_HEALTH_CHECK_INTERVAL_MILLIS} milliseconds is used by default.
     */
    public ConsulEndpointGroupBuilder registryFetchInterval(Duration registryFetchInterval) {
        requireNonNull(registryFetchInterval, "registryFetchInterval");
        checkArgument(!registryFetchInterval.isZero() && !registryFetchInterval.isNegative(),
                      "registryFetchInterval: %s (expected: > 0)",
                      registryFetchInterval);
        return registryFetchIntervalMillis(registryFetchInterval.toMillis());
    }

    /**
     * Sets the interval between fetching registry requests in milliseconds.
     * If not set, {@value #DEFAULT_HEALTH_CHECK_INTERVAL_MILLIS} is used by default.
     */
    public ConsulEndpointGroupBuilder registryFetchIntervalMillis(long registryFetchIntervalMillis) {
        checkArgument(registryFetchIntervalMillis > 0, "registryFetchIntervalMillis: %s (expected: > 0)",
                      registryFetchIntervalMillis);
        this.registryFetchIntervalMillis = registryFetchIntervalMillis;
        return this;
    }

    /**
     * Sets whether to use <a href="https://www.consul.io/api/health.html">Health HTTP endpoint</a>.
     * make sure that your target endpoints are health-checked by Consul before enabling this feature.
     *
     * @see ConsulUpdatingListenerBuilder#checkUri(URI)
     */
    public ConsulEndpointGroupBuilder useHealthEndpoints(boolean useHealthyEndpoints) {
        this.useHealthyEndpoints = useHealthyEndpoints;
        return this;
    }

    @Override
    public ConsulEndpointGroupBuilder consulApiVersion(String consulApiVersion) {
        consulClientBuilder.consulApiVersion(consulApiVersion);
        return this;
    }

    @Override
    public ConsulEndpointGroupBuilder consulToken(String consulToken) {
        consulClientBuilder.consulToken(consulToken);
        return this;
    }

    /**
     * Returns a newly-created {@link ConsulEndpointGroup}.
     */
    public ConsulEndpointGroup build() {
        return new ConsulEndpointGroup(selectionStrategy, consulClientBuilder.build(), serviceName,
                                       registryFetchIntervalMillis, useHealthyEndpoints);
    }
}
