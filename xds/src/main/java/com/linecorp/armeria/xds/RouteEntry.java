/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.xds;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpPreClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.RpcPreClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.xds.internal.DelegatingHttpClient;
import com.linecorp.armeria.xds.internal.DelegatingRpcClient;

import io.envoyproxy.envoy.config.route.v3.RetryPolicy;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * Represents a {@link Route}.
 */
public final class RouteEntry {

    private final Route route;
    @Nullable
    private final ClusterSnapshot clusterSnapshot;
    private final Map<String, Any> filterConfigs;
    private final int index;
    private final HttpClient httpClient;
    private final RpcClient rpcClient;
    private final HttpPreClient httpPreClient;
    private final RpcPreClient rpcPreClient;
    private final RouteEntryMatcher matcher;

    RouteEntry(Route route, @Nullable ClusterSnapshot clusterSnapshot, int index,
               @Nullable ListenerXdsResource listenerResource, RouteXdsResource routeResource,
               VirtualHostXdsResource vhostResource, XdsExtensionRegistry extensionRegistry) {
        this.route = route;
        this.clusterSnapshot = clusterSnapshot;
        this.index = index;
        matcher = new RouteEntryMatcher(route.getMatch());

        // Merge per_filter_config: route-config level < vhost level < route level
        final Map<String, Any> routeConfigFilterConfigs =
                routeResource.resource().getTypedPerFilterConfigMap();
        final Map<String, Any> vhostFilterConfigs =
                vhostResource.resource().getTypedPerFilterConfigMap();
        final Map<String, Any> routeFilterConfigs =
                route.getTypedPerFilterConfigMap();
        filterConfigs = FilterUtil.mergeFilterConfigs(
                FilterUtil.mergeFilterConfigs(routeConfigFilterConfigs, vhostFilterConfigs),
                routeFilterConfigs);

        // Extract upstream HTTP filters from the Router (last filter in HCM filter chain)
        final List<HttpFilter> upstreamFilters;
        if (listenerResource != null && listenerResource.router() != null) {
            upstreamFilters = listenerResource.router().getUpstreamHttpFiltersList();
        } else {
            upstreamFilters = ImmutableList.of();
        }

        // Determine retry policy
        final RetryPolicy retryPolicy = route.getRoute().getRetryPolicy();
        final RetryPolicy effectiveRetryPolicy =
                retryPolicy == RetryPolicy.getDefaultInstance() ? null : retryPolicy;
        final ClientDecoration clientDecoration = FilterUtil.buildUpstreamFilter(
                extensionRegistry, upstreamFilters, filterConfigs, effectiveRetryPolicy);
        httpClient = clientDecoration.decorate(DelegatingHttpClient.of());
        rpcClient = clientDecoration.rpcDecorate(DelegatingRpcClient.of());

        // Build downstream filters (HCM http_filters) with per-route config
        final List<HttpFilter> hcmHttpFilters;
        if (listenerResource != null && listenerResource.connectionManager() != null) {
            hcmHttpFilters = listenerResource.connectionManager().getHttpFiltersList();
        } else {
            hcmHttpFilters = ImmutableList.of();
        }
        final ClientPreprocessors downstreamPreprocessors = FilterUtil.buildDownstreamFilter(
                extensionRegistry, hcmHttpFilters, filterConfigs);
        httpPreClient = downstreamPreprocessors.decorate(DelegatingHttpClient.of());
        rpcPreClient = downstreamPreprocessors.rpcDecorate(DelegatingRpcClient.of());
    }

    /**
     * The {@link Route}.
     */
    public Route route() {
        return route;
    }

    /**
     * The {@link ClusterSnapshot} that is represented by {@link RouteAction#getCluster()}.
     * If the {@link RouteAction} does not reference a cluster, the returned value may be {@code null}.
     */
    @Nullable
    public ClusterSnapshot clusterSnapshot() {
        return clusterSnapshot;
    }

    /**
     * Returns the raw per-route filter config {@link Any} from
     * {@link Route#getTypedPerFilterConfigMap()}, or {@code null} if not present.
     *
     * @param filterName the filter name represented by {@link HttpFilter#getName()}
     */
    @Nullable
    public Any filterConfig(String filterName) {
        return filterConfigs.get(filterName);
    }

    /**
     * Returns the downstream {@link HttpPreClient} chain for this route.
     */
    @UnstableApi
    public HttpPreClient httpPreClient() {
        return httpPreClient;
    }

    /**
     * Returns the downstream {@link RpcPreClient} chain for this route.
     */
    @UnstableApi
    public RpcPreClient rpcPreClient() {
        return rpcPreClient;
    }

    /**
     * Returns whether this route matches the specified {@link ClientRequestContext}.
     */
    public boolean matches(ClientRequestContext ctx) {
        return matcher.matches(ctx);
    }

    /**
     * Applies upstream filters to a request corresponding to the supplied {@link ClientRequestContext}.
     */
    @UnstableApi
    public void applyUpstreamFilter(ClientRequestContext ctx) {
        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        if (ctxExt == null) {
            return;
        }
        ctxExt.httpClientCustomizer(actualClient -> {
            DelegatingHttpClient.setDelegate(ctx, actualClient);
            return httpClient;
        });
        ctxExt.rpcClientCustomizer(actualClient -> {
            DelegatingRpcClient.setDelegate(ctx, actualClient);
            return rpcClient;
        });
    }

    /**
     * The index of this route within a {@link VirtualHost}.
     */
    public int index() {
        return index;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RouteEntry that = (RouteEntry) o;
        return Objects.equals(route, that.route) &&
               Objects.equals(index, that.index) &&
               Objects.equals(clusterSnapshot, that.clusterSnapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(route, clusterSnapshot, index);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("index", index)
                          .add("clusterSnapshot", clusterSnapshot)
                          .toString();
    }
}
