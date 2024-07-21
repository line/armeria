/*
 * Copyright 2024 LY Corporation

 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.client.nacos;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;

import com.linecorp.armeria.client.endpoint.AbstractDynamicEndpointGroupBuilder;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.nacos.NacosConfigSetters;
import com.linecorp.armeria.internal.nacos.NacosClient;
import com.linecorp.armeria.internal.nacos.NacosClientBuilder;

/**
 * A builder class for {@link NacosEndpointGroup}.
 * <h2>Examples</h2>
 * <pre>{@code
 * NacosEndpointGroup endpointGroup = NacosEndpointGroup.builder(nacosUri, "myService")
 *                                                      .build();
 * WebClient client = WebClient.of(SessionProtocol.HTTPS, endpointGroup);
 * }</pre>
 */
@UnstableApi
public final class NacosEndpointGroupBuilder
        extends AbstractDynamicEndpointGroupBuilder<NacosEndpointGroupBuilder>
        implements NacosConfigSetters<NacosEndpointGroupBuilder> {

    private static final long DEFAULT_CHECK_INTERVAL_MILLIS = 10_000;

    private final NacosClientBuilder nacosClientBuilder;
    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();
    private long registryFetchIntervalMillis = DEFAULT_CHECK_INTERVAL_MILLIS;

    NacosEndpointGroupBuilder(URI nacosUri, String serviceName) {
        super(Flags.defaultResponseTimeoutMillis());
        nacosClientBuilder = NacosClient.builder(nacosUri, requireNonNull(serviceName, "serviceName"));
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} of the {@link NacosEndpointGroup}.
     */
    public NacosEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return this;
    }

    @Override
    public NacosEndpointGroupBuilder namespaceId(String namespaceId) {
        nacosClientBuilder.namespaceId(namespaceId);
        return this;
    }

    @Override
    public NacosEndpointGroupBuilder groupName(String groupName) {
        nacosClientBuilder.groupName(groupName);
        return this;
    }

    @Override
    public NacosEndpointGroupBuilder clusterName(String clusterName) {
        nacosClientBuilder.clusterName(clusterName);
        return this;
    }

    @Override
    public NacosEndpointGroupBuilder app(String app) {
        nacosClientBuilder.app(app);
        return this;
    }

    @Override
    public NacosEndpointGroupBuilder nacosApiVersion(String nacosApiVersion) {
        nacosClientBuilder.nacosApiVersion(nacosApiVersion);
        return this;
    }

    @Override
    public NacosEndpointGroupBuilder authorization(String username, String password) {
        nacosClientBuilder.authorization(username, password);
        return this;
    }

    /**
     * Sets the healthy to retrieve only healthy instances from Nacos.
     * Make sure that your target endpoints are health-checked by Nacos before enabling this feature.
     * If not set, false is used by default.
     */
    public NacosEndpointGroupBuilder useHealthyEndpoints(boolean useHealthyEndpoints) {
        nacosClientBuilder.healthyOnly(useHealthyEndpoints);
        return this;
    }

    /**
     * Sets the interval between fetching registry requests.
     * If not set, {@value #DEFAULT_CHECK_INTERVAL_MILLIS} milliseconds is used by default.
     */
    public NacosEndpointGroupBuilder registryFetchInterval(Duration registryFetchInterval) {
        requireNonNull(registryFetchInterval, "registryFetchInterval");
        return registryFetchIntervalMillis(registryFetchInterval.toMillis());
    }

    /**
     * Sets the interval between fetching registry requests.
     * If not set, {@value #DEFAULT_CHECK_INTERVAL_MILLIS} milliseconds is used by default.
     */
    public NacosEndpointGroupBuilder registryFetchIntervalMillis(long registryFetchIntervalMillis) {
        checkArgument(registryFetchIntervalMillis > 0, "registryFetchIntervalMillis: %s (expected: > 0)",
                      registryFetchIntervalMillis);
        this.registryFetchIntervalMillis = registryFetchIntervalMillis;
        return this;
    }

    /**
     * Returns a newly-created {@link NacosEndpointGroup}.
     */
    public NacosEndpointGroup build() {
        return new NacosEndpointGroup(selectionStrategy, shouldAllowEmptyEndpoints(), selectionTimeoutMillis(),
                                      nacosClientBuilder.build(), registryFetchIntervalMillis);
    }
}
