/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.client.nacos;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.AbstractDynamicEndpointGroupBuilder;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.nacos.NacosClient;
import com.linecorp.armeria.internal.nacos.NacosClientBuilder;

/**
 * A builder class for {@link NacosEndpointGroup}.
 * <h2>Examples</h2>
 * <pre>{@code
 * NacosEndpointGroup endpointGroup = NacosEndpointGroup.builder(nacosUri, "myService")
 *                                                      .build();
 * List<Endpoint> endpoints = endpointGroup.endpoints();
 * sb.serverListener(listener);
 * }</pre>
 */
public class NacosEndpointGroupBuilder extends AbstractDynamicEndpointGroupBuilder {

    private static final long DEFAULT_CHECK_INTERVAL_MILLIS = 30_000;

    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();

    private final String serviceName;

    private long registryFetchIntervalMillis = DEFAULT_CHECK_INTERVAL_MILLIS;

    @Nullable
    private String namespaceId;

    @Nullable
    private String groupName;

    @Nullable
    private String clusterName;

    @Nullable
    private String app;

    private boolean useHealthyEndpoints;

    private final NacosClientBuilder nacosClientBuilder;

    NacosEndpointGroupBuilder(URI nacosUri, String serviceName) {
        super(Flags.defaultResponseTimeoutMillis());
        this.serviceName = requireNonNull(serviceName, "serviceName");
        nacosClientBuilder = NacosClient.builder(nacosUri);
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} of the {@link NacosEndpointGroup}.
     */
    public NacosEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return this;
    }

    /**
     * Sets the 'namespaceId' parameter used to filter instances in a Nacos query.
     * This method configures the NacosEndpointGroup to query only instances
     * that match the specified 'namespaceId' value.
     */
    public NacosEndpointGroupBuilder namespaceId(String namespaceId) {
        this.namespaceId = requireNonNull(namespaceId, "namespaceId");
        return this;
    }

    /**
     * Sets the 'groupName' parameter used to filter instances in a Nacos query.
     * This method configures the NacosEndpointGroup to query only instances
     * that match the specified 'groupName' value.
     */
    public NacosEndpointGroupBuilder groupName(String groupName) {
        this.groupName = requireNonNull(groupName);
        return this;
    }

    /**
     * Sets the 'clusterName' parameter used to filter instances in a Nacos query.
     * This method configures the NacosEndpointGroup to query only instances
     * that match the specified 'clusterName' value.
     */
    public NacosEndpointGroupBuilder clusterName(String clusterName) {
        this.clusterName = requireNonNull(clusterName);
        return this;
    }

    /**
     * Sets the 'app' parameter used to filter instances in a Nacos query.
     * This method configures the NacosEndpointGroup
     * to query only instances that match the specified 'app' value.
     */
    public NacosEndpointGroupBuilder app(String app) {
        this.app = requireNonNull(app);
        return this;
    }

    /**
     * Sets the healthy to retrieve only healthy instances from Nacos.
     * Make sure that your target endpoints are health-checked by Nacos before enabling this feature.
     * If not set, false is used by default.
     */
    public NacosEndpointGroupBuilder useHealthyEndpoints(boolean useHealthyEndpoints) {
        this.useHealthyEndpoints = useHealthyEndpoints;
        return this;
    }

    /**
     * Sets the interval between fetching registry requests.
     * If not set, {@value #DEFAULT_CHECK_INTERVAL_MILLIS} milliseconds is used by default.
     */
    public NacosEndpointGroupBuilder registryFetchInterval(Duration registryFetchInterval) {
        requireNonNull(registryFetchInterval, "registryFetchInterval");
        checkArgument(!registryFetchInterval.isZero() && !registryFetchInterval.isNegative(),
                "registryFetchInterval: %s (expected: > 0)",
                registryFetchInterval);
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
     * Sets the username and password pair for Nacos's API.
     * Please refer to the
     * <a href=https://nacos.io/en-us/docs/v2/guide/user/auth.html>Nacos Authentication Document</a>
     * for more details.
     *
     * @param username the username for access Nacos API, default: {@code null}
     * @param password the password for access Nacos API, default: {@code null}
     */
    public NacosEndpointGroupBuilder authorization(String username, String password) {
        nacosClientBuilder.authorization(username, password);
        return this;
    }

    /**
     * Returns a newly-created {@link NacosEndpointGroup}.
     */
    public NacosEndpointGroup build() {
        return new NacosEndpointGroup(selectionStrategy, shouldAllowEmptyEndpoints(),
                                      selectionTimeoutMillis(), nacosClientBuilder.build(),
                                      serviceName, registryFetchIntervalMillis, namespaceId,
                                      groupName, clusterName, app, useHealthyEndpoints);
    }

    @Override
    public NacosEndpointGroupBuilder allowEmptyEndpoints(boolean allowEmptyEndpoints) {
        return (NacosEndpointGroupBuilder) super.allowEmptyEndpoints(allowEmptyEndpoints);
    }

    /**
     * Sets the timeout to wait until a successful {@link Endpoint} selection.
     * {@link Duration#ZERO} disables the timeout.
     * If unspecified, {@link Flags#defaultResponseTimeoutMillis()} is used by default.
     */
    @Override
    public NacosEndpointGroupBuilder selectionTimeout(Duration selectionTimeout) {
        return (NacosEndpointGroupBuilder) super.selectionTimeout(selectionTimeout);
    }

    /**
     * Sets the timeout to wait until a successful {@link Endpoint} selection.
     * {@code 0} disables the timeout.
     * If unspecified, {@link Flags#defaultResponseTimeoutMillis()} is used by default.
     */
    @Override
    public NacosEndpointGroupBuilder selectionTimeoutMillis(long selectionTimeoutMillis) {
        return (NacosEndpointGroupBuilder) super.selectionTimeoutMillis(selectionTimeoutMillis);
    }
}
