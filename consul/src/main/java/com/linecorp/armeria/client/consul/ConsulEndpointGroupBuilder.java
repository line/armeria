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
    private static final long DEFAULT_HEALTH_CHECK_INTERVAL_SECONDS = 10;

    private final String serviceName;
    private long registryFetchIntervalSeconds = DEFAULT_HEALTH_CHECK_INTERVAL_SECONDS;
    private boolean useHealthyEndpoints;

    ConsulEndpointGroupBuilder(String serviceName) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
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
     * Sets the interval between fetching registry requests.
     * If not set, {@value #DEFAULT_HEALTH_CHECK_INTERVAL_SECONDS} is used by default.
     */
    public ConsulEndpointGroupBuilder registryFetchInterval(Duration registryFetchInterval) {
        requireNonNull(registryFetchInterval, "registryFetchInterval");
        final long seconds = registryFetchInterval.getSeconds();
        checkArgument(seconds > 0, "registryFetchInterval.getSeconds(): %s (expected: > 0)", seconds);
        return registryFetchIntervalSeconds(seconds);
    }

    /**
     * Sets the interval between fetching registry requests.he interval between fetching registry requests.
     * If not set {@value #DEFAULT_HEALTH_CHECK_INTERVAL_SECONDS} is used by default.
     */
    public ConsulEndpointGroupBuilder registryFetchIntervalSeconds(long registryFetchIntervalSeconds) {
        checkArgument(registryFetchIntervalSeconds > 0, "registryFetchIntervalSeconds: %s (expected: > 0)",
                      registryFetchIntervalSeconds);
        this.registryFetchIntervalSeconds = registryFetchIntervalSeconds;
        return this;
    }

    /**
     * Sets whether to use <a href="https://www.consul.io/api/health.html">Health HTTP endpoint</a>.
     * Before enabling this feature, make sure that your target endpoints are health-checked by Consul.
     *
     * @see ConsulUpdatingListenerBuilder#checkUri(URI)
     */
    public ConsulEndpointGroupBuilder useHealthEndpoints(boolean useHealthyEndpoints) {
        this.useHealthyEndpoints = useHealthyEndpoints;
        return this;
    }

    /**
     * Returns a newly-created {@link ConsulEndpointGroup}.
     */
    public ConsulEndpointGroup build() {
        return new ConsulEndpointGroup(buildClient(), serviceName, registryFetchIntervalSeconds,
                                       useHealthyEndpoints);
    }
}
