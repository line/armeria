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
package com.linecorp.armeria.client.eureka;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;

/**
 * Builds a {@link EurekaEndpointGroup}.
 */
public final class EurekaEndpointGroupBuilder {

    private static final long DEFAULT_REGISTRY_FETCH_INTERVAL_SECONDS = 30;

    private final URI eurekaUri;

    @Nullable
    private String appName;

    @Nullable
    private String instanceId;

    @Nullable
    private String vipAddress;

    @Nullable
    private String secureVipAddress;

    private long registryFetchIntervalSeconds = DEFAULT_REGISTRY_FETCH_INTERVAL_SECONDS;

    @Nullable
    private List<String> regions;
    @Nullable
    private Consumer<WebClientBuilder> customizer;

    EurekaEndpointGroupBuilder(URI eurekaUri) {
        this.eurekaUri = requireNonNull(eurekaUri, "eurekaUri");
    }

    /**
     * Sets the specified {@code regions}. {@link EurekaEndpointGroup} will retrieve the registry information
     * which belongs to the {@code regions}.
     */
    public EurekaEndpointGroupBuilder regions(String... regions) {
        return regions(ImmutableList.copyOf(requireNonNull(regions, "regions")));
    }

    /**
     * Sets the specified {@code regions}. {@link EurekaEndpointGroup} will retrieve the registry information
     * which belongs to the {@code regions}.
     */
    public EurekaEndpointGroupBuilder regions(Iterable<String> regions) {
        this.regions = ImmutableList.copyOf(requireNonNull(regions, "regions"));
        return this;
    }

    /**
     * Sets the specified {@code appName}. {@link EurekaEndpointGroup} will retrieve the registry information
     * whose application name is the specified {@code appName}.
     *
     * @throws IllegalStateException if {@link #vipAddress(String)} or {@link #secureVipAddress(String)} is
     *                               called already
     */
    public EurekaEndpointGroupBuilder appName(String appName) {
        requireNonNull(appName, "appName");
        checkState(vipAddress == null && secureVipAddress == null,
                   "cannot set appName with the %s.", vipAddress != null ? "vipAddress" : "secureVipAddress");
        this.appName = appName;
        return this;
    }

    /**
     * Sets the specified {@code instanceId}. {@link EurekaEndpointGroup} will only retrieve the registry
     * information whose instance ID is the specified {@code instanceId}.
     *
     * @throws IllegalStateException if {@link #vipAddress(String)} or {@link #secureVipAddress(String)} is
     *                               called already
     */
    public EurekaEndpointGroupBuilder instanceId(String instanceId) {
        requireNonNull(instanceId, "instanceId");
        checkState(vipAddress == null && secureVipAddress == null,
                   "cannot set instanceId with the %s.",
                   vipAddress != null ? "vipAddress" : "secureVipAddress");
        this.instanceId = instanceId;
        return this;
    }

    /**
     * Sets the specified {@code vipAddress}. {@link EurekaEndpointGroup} will retrieve the registry information
     * whose VIP address is the specified {@code vipAddress}.
     *
     * @throws IllegalStateException if {@link #appName(String)}, {@link #instanceId(String)} or
     *                               {@link #secureVipAddress(String)} is called already
     */
    public EurekaEndpointGroupBuilder vipAddress(String vipAddress) {
        requireNonNull(vipAddress, "vipAddress");
        checkState(appName == null && instanceId == null && secureVipAddress == null,
                   "cannot set vipAddress with the %s.",
                   secureVipAddress != null ? "secureVipAddress" : "appName or instanceId");
        this.vipAddress = vipAddress;
        return this;
    }

    /**
     * Sets the specified {@code secureVipAddress}. {@link EurekaEndpointGroup} will retrieve the
     * registry information whose VIP address is the specified {@code secureVipAddress}.
     *
     * @throws IllegalStateException if {@link #appName(String)}, {@link #instanceId(String)} or
     *                               {@link #vipAddress(String)} is called already
     */
    public EurekaEndpointGroupBuilder secureVipAddress(String secureVipAddress) {
        requireNonNull(secureVipAddress, "secureVipAddress");
        checkState(appName == null && instanceId == null && vipAddress == null,
                   "cannot set secureVipAddress with the %s.",
                   vipAddress != null ? "vipAddress" : "appName or instanceId");
        this.secureVipAddress = secureVipAddress;
        return this;
    }

    /**
     * Sets the interval between fetching registry requests.
     */
    public EurekaEndpointGroupBuilder registryFetchInterval(Duration registryFetchInterval) {
        requireNonNull(registryFetchInterval, "registryFetchInterval");
        final long seconds = registryFetchInterval.getSeconds();
        checkArgument(seconds > 0, "registryFetchInterval.getSeconds(): %s (expected: > 0)", seconds);
        return registryFetchIntervalSeconds(seconds);
    }

    /**
     * Sets the interval between fetching registry requests in seconds.
     */
    public EurekaEndpointGroupBuilder registryFetchIntervalSeconds(long registryFetchIntervalSeconds) {
        checkArgument(registryFetchIntervalSeconds > 0, "registryFetchIntervalSeconds: %s (expected: > 0)",
                      registryFetchIntervalSeconds);
        this.registryFetchIntervalSeconds = registryFetchIntervalSeconds;
        return this;
    }

    /**
     * Adds the {@link Consumer} which can arbitrarily configure the {@link WebClientBuilder} that will be
     * applied to the {@link WebClient} that sends requests to Eureka.
     */
    public EurekaEndpointGroupBuilder webClientCustomizer(Consumer<WebClientBuilder> customizer) {
        this.customizer = requireNonNull(customizer, "customizer");
        return this;
    }

    /**
     * Returns a new {@link EurekaEndpointGroup} created with the properties set so far.
     */
    public EurekaEndpointGroup build() {
        return new EurekaEndpointGroup(eurekaUri, registryFetchIntervalSeconds, appName, instanceId, vipAddress,
                                       secureVipAddress, regions, customizer);
    }
}
