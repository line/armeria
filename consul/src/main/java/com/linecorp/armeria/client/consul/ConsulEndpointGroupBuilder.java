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

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.internal.consul.ConsulClient;
import com.linecorp.armeria.server.consul.ConsulUpdatingListenerBuilder;

/**
 * A builder class for {@link ConsulEndpointGroup}.
 */
public final class ConsulEndpointGroupBuilder {

    private static final long DEFAULT_HEALTH_CHECK_INTERVAL_SECONDS = 10;

    private long registryFetchIntervalSeconds = DEFAULT_HEALTH_CHECK_INTERVAL_SECONDS;
    private final String serviceName;

    @Nullable
    private URI consulUri;
    @Nullable
    private ConsulClient consulClient;
    @Nullable
    private String token;

    private boolean useHealthyEndpoints;

    ConsulEndpointGroupBuilder(String serviceName) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
    }

    /**
     * Sets the specified {@code consulUri}.
     */
    public ConsulEndpointGroupBuilder consulUri(URI consulUri) {
        this.consulUri = requireNonNull(consulUri, "consulUri");
        return this;
    }

    /**
     * Sets the specified {@code consulUri}.
     */
    public ConsulEndpointGroupBuilder consulUri(String consulUri) {
        requireNonNull(consulUri, "consulUri");
        checkArgument(!consulUri.isEmpty(), "consulUri can't be empty");
        this.consulUri = URI.create(consulUri);
        return this;
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
     * Sets the specified {@code token} for accessing Consul server.
     */
    public ConsulEndpointGroupBuilder token(String token) {
        this.token = requireNonNull(token, "token");
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
        if (consulClient == null) {
            consulClient = new ConsulClient(consulUri, token);
        }
        return new ConsulEndpointGroup(consulClient, serviceName, registryFetchIntervalSeconds,
                                       useHealthyEndpoints);
    }

    @VisibleForTesting
    ConsulEndpointGroupBuilder consulClient(ConsulClient consulClient) {
        this.consulClient = requireNonNull(consulClient, "consulClient");
        return this;
    }
}
