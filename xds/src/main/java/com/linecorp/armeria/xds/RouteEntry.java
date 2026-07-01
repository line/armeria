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

import java.util.List;
import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.HttpService;

import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

/**
 * Represents a {@link Route}.
 */
public final class RouteEntry {

    private final Route route;
    private final RouteClusterResolver routeResolver;
    private final int index;
    private final HttpService httpService;
    private final RouteEntryMatcher matcher;

    RouteEntry(Route route, RouteClusterResolver routeResolver, int index,
               HttpService httpService) {
        this.route = route;
        this.routeResolver = routeResolver;
        this.index = index;
        this.httpService = httpService;
        matcher = new RouteEntryMatcher(route.getMatch());
    }

    /**
     * The {@link Route}.
     */
    public Route route() {
        return route;
    }

    /**
     * The {@link ClusterSnapshot} that is represented by {@link RouteAction#getCluster()}.
     * If the {@link RouteAction} does not reference a single cluster (e.g. when using weighted clusters),
     * the returned value may be {@code null}.
     * Use {@link #resolve()} for a unified way to obtain a target regardless of the cluster specifier.
     */
    @Nullable
    public ClusterSnapshot clusterSnapshot() {
        return routeResolver.clusterSnapshot();
    }

    /**
     * Returns the list of {@link WeightedClusterSnapshot}s for this route, or {@code null}
     * if this route uses a single cluster or no cluster.
     */
    @Nullable
    @UnstableApi
    public List<WeightedClusterSnapshot> weightedClusters() {
        return routeResolver.weightedClusters();
    }

    /**
     * Selects a target for this route. For single-cluster routes, returns the target directly.
     * For weighted-cluster routes, performs a weighted random selection.
     * Returns {@code null} if no target is configured.
     */
    @Nullable
    @UnstableApi
    public RouteCluster resolve() {
        return routeResolver.resolve();
    }

    /**
     * Returns the composed {@link HttpService} from the HTTP filters for this route.
     */
    @UnstableApi
    public HttpService httpService() {
        return httpService;
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
               Objects.equals(routeResolver, that.routeResolver);
    }

    @Override
    public int hashCode() {
        return Objects.hash(route, routeResolver, index);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("index", index)
                          .add("routeResolver", routeResolver)
                          .toString();
    }

    String toDebugString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("index", index)
                          .add("route", route)
                          .add("routeResolver", routeResolver.toDebugString())
                          .toString();
    }
}
