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

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Objects;

import com.google.common.base.MoreObjects;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpPreClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.RpcPreClient;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.xds.internal.DelegatingHttpClient;
import com.linecorp.armeria.xds.internal.DelegatingRpcClient;

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
    private final HttpService httpService;
    private final HttpPreClient httpPreClient;
    private final RpcPreClient rpcPreClient;
    private final RouteEntryMatcher matcher;

    RouteEntry(Route route, @Nullable ClusterSnapshot clusterSnapshot, int index,
               Map<String, Any> filterConfigs,
               ClientPreprocessors downstreamPreprocessors,
               @Nullable ClientDecoration retryDecoration,
               ClientDecoration upstreamDecoration, HttpService httpService) {
        this.route = route;
        this.clusterSnapshot = clusterSnapshot;
        this.index = index;
        this.filterConfigs = filterConfigs;
        this.httpService = httpService;
        matcher = new RouteEntryMatcher(route.getMatch());

        // Pre-build the full cluster chain: retry → ClusterFilter → upstream filters → delegate
        HttpClient clusterHttp = ClusterFilterFactory.DECORATION.decorate(
                upstreamDecoration.decorate(DelegatingHttpClient.of()));
        if (retryDecoration != null) {
            clusterHttp = retryDecoration.decorate(clusterHttp);
        }
        httpClient = clusterHttp;
        rpcClient = ClusterFilterFactory.DECORATION.rpcDecorate(
                upstreamDecoration.rpcDecorate(DelegatingRpcClient.of()));

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
     * Returns the composed {@link HttpService} from the HTTP filters for this route.
     */
    @UnstableApi
    public HttpService httpService() {
        return httpService;
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
     * Returns the pre-built {@link HttpClient} chain for this route. This chain includes
     * retry, cluster filter, and upstream HTTP filters ending with a {@link DelegatingHttpClient}.
     */
    @UnstableApi
    public HttpClient httpClient() {
        return httpClient;
    }

    /**
     * Returns the pre-built {@link RpcClient} chain for this route. This chain includes
     * cluster filter and upstream RPC filters ending with a {@link DelegatingRpcClient}.
     */
    @UnstableApi
    public RpcClient rpcClient() {
        return rpcClient;
    }

    /**
     * Returns whether this route matches the specified {@link RequestContext}.
     */
    public boolean matches(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        return matcher.matches(ctx);
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

    String toDebugString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("index", index)
                          .add("route", route)
                          .add("clusterSnapshot",
                               SnapshotUtil.debugString(clusterSnapshot,
                                                        ClusterSnapshot::toDebugString))
                          .toString();
    }
}
