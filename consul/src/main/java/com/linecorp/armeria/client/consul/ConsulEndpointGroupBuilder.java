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

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.consul.ConsulClientBuilder;
import com.linecorp.armeria.server.consul.ConsulUpdatingListenerBuilder;

/**
 * A builder class for {@link ConsulEndpointGroup}.
 */
public final class ConsulEndpointGroupBuilder extends ConsulClientBuilder {
    private static final long DEFAULT_HEALTH_CHECK_INTERVAL_MILLIS = 10_000;

    private final String serviceName;
    private long registryFetchIntervalMillis = DEFAULT_HEALTH_CHECK_INTERVAL_MILLIS;
    private boolean useHealthyEndpoints;

    ConsulEndpointGroupBuilder(String serviceName) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
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
    public ConsulEndpointGroupBuilder consulUri(URI consulUri) {
        return (ConsulEndpointGroupBuilder) super.consulUri(consulUri);
    }

    @Override
    public ConsulEndpointGroupBuilder consulUri(String consulUri) {
        return (ConsulEndpointGroupBuilder) super.consulUri(consulUri);
    }

    @Override
    public ConsulEndpointGroupBuilder consulProtocol(SessionProtocol consulProtocol) {
        return (ConsulEndpointGroupBuilder) super.consulProtocol(consulProtocol);
    }

    @Override
    public ConsulEndpointGroupBuilder consulAddress(String consulAddress) {
        return (ConsulEndpointGroupBuilder) super.consulAddress(consulAddress);
    }

    @Override
    public ConsulEndpointGroupBuilder consulPort(int consulPort) {
        return (ConsulEndpointGroupBuilder) super.consulPort(consulPort);
    }

    @Override
    public ConsulEndpointGroupBuilder consulApiVersion(String consulApiVersion) {
        return (ConsulEndpointGroupBuilder) super.consulApiVersion(consulApiVersion);
    }

    @Override
    public ConsulEndpointGroupBuilder consulToken(String consulToken) {
        return (ConsulEndpointGroupBuilder) super.consulToken(consulToken);
    }

    /**
     * Returns a newly-created {@link ConsulEndpointGroup}.
     */
    public ConsulEndpointGroup build() {
        return new ConsulEndpointGroup(buildClient(), serviceName, registryFetchIntervalMillis,
                                       useHealthyEndpoints);
    }
}
