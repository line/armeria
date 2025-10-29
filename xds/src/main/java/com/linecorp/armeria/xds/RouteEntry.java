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

import static com.linecorp.armeria.xds.FilterUtil.toParsedFilterConfigs;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.retry.RetryingClient;
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
    private final Map<String, ParsedFilterConfig> filterConfigs;
    private final int index;
    @Nullable
    private final Function<? super HttpClient, RetryingClient> retryingDecorator;
    private final ClientDecoration upstreamFilter;
    private final RouteEntryMatcher matcher;
    @Nullable
    private final HttpClient httpClient;
    @Nullable
    private final RpcClient rpcClient;

    RouteEntry(Route route, @Nullable ClusterSnapshot clusterSnapshot, int index) {
        this.route = route;
        this.clusterSnapshot = clusterSnapshot;
        filterConfigs = toParsedFilterConfigs(route.getTypedPerFilterConfigMap());
        this.index = index;
        upstreamFilter = ClientDecoration.of();
        if (route.getRoute().getRetryPolicy() != RetryPolicy.getDefaultInstance()) {
            retryingDecorator = new RetryStateFactory(route.getRoute().getRetryPolicy()).retryingDecorator();
        } else {
            retryingDecorator = null;
        }
        matcher = new RouteEntryMatcher(route.getMatch());

        httpClient = upstreamFilter.decorate(DelegatingHttpClient.of());
        rpcClient = upstreamFilter.rpcDecorate(DelegatingRpcClient.of());
    }

    private RouteEntry(Route route, @Nullable ClusterSnapshot clusterSnapshot, int index,
                       Map<String, ParsedFilterConfig> filterConfigs, List<HttpFilter> upstreamFilters,
                       @Nullable Function<? super HttpClient, RetryingClient> retryingDecorator,
                       RouteEntryMatcher matcher) {
        this.route = route;
        this.clusterSnapshot = clusterSnapshot;
        this.filterConfigs = filterConfigs;
        this.index = index;
        upstreamFilter = FilterUtil.buildUpstreamFilter(upstreamFilters, filterConfigs, retryingDecorator);
        this.retryingDecorator = retryingDecorator;
        this.matcher = matcher;

        httpClient = upstreamFilter.decorate(DelegatingHttpClient.of());
        rpcClient = upstreamFilter.rpcDecorate(DelegatingRpcClient.of());
    }

    RouteEntry withFilterConfigs(Map<String, ParsedFilterConfig> parentFilterConfigs,
                                 List<HttpFilter> upstreamFilters) {
        final Map<String, ParsedFilterConfig> mergedFilterConfigs =
                FilterUtil.mergeFilterConfigs(parentFilterConfigs, filterConfigs);
        return new RouteEntry(route, clusterSnapshot, index, mergedFilterConfigs, upstreamFilters,
                              retryingDecorator, matcher);
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
     * Returns the parsed {@link Route#getTypedPerFilterConfigMap()}.
     *
     * @param filterName the filter name represented by {@link HttpFilter#getName()}
     */
    @Nullable
    public ParsedFilterConfig filterConfig(String filterName) {
        return filterConfigs.get(filterName);
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
        if (httpClient != null) {
            ctxExt.httpClientCustomizer(actualClient -> {
                DelegatingHttpClient.setDelegate(ctx, actualClient);
                return httpClient;
            });
        }
        if (rpcClient != null) {
            ctxExt.rpcClientCustomizer(actualClient -> {
                DelegatingRpcClient.setDelegate(ctx, actualClient);
                return rpcClient;
            });
        }
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
