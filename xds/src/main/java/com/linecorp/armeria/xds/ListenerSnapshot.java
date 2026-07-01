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

package com.linecorp.armeria.xds;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ConnectionContext;

import io.envoyproxy.envoy.config.listener.v3.Listener;

/**
 * A snapshot of a {@link Listener} resource.
 */
@UnstableApi
public final class ListenerSnapshot implements Snapshot<ListenerXdsResource> {

    private final ListenerXdsResource listenerXdsResource;
    private final List<FilterChainSnapshot> filterChains;
    @Nullable
    private final FilterChainSnapshot defaultFilterChain;
    @Nullable
    private final RouteSnapshot apiListenerRoute;
    @Nullable
    private final RouteSnapshot defaultRouteSnapshot;

    ListenerSnapshot(ListenerXdsResource listenerXdsResource,
                     Optional<RouteSnapshot> apiListenerRoute,
                     List<FilterChainSnapshot> filterChains,
                     Optional<FilterChainSnapshot> defaultFilterChain) {
        this.listenerXdsResource = listenerXdsResource;
        this.filterChains = filterChains;
        this.defaultFilterChain = defaultFilterChain.orElse(null);
        this.apiListenerRoute = apiListenerRoute.orElse(null);
        defaultRouteSnapshot = defaultRouteSnapshot();
    }

    @Override
    public ListenerXdsResource xdsResource() {
        return listenerXdsResource;
    }

    /**
     * Returns a {@link RouteSnapshot} for this {@link Listener}, checking
     * the {@code api_listener} first, then falling back to the first filter chain
     * or default filter chain that contains a route.
     */
    @Nullable
    public RouteSnapshot routeSnapshot() {
        return defaultRouteSnapshot;
    }

    @Nullable
    private RouteSnapshot defaultRouteSnapshot() {
        if (apiListenerRoute != null) {
            return apiListenerRoute;
        }
        for (FilterChainSnapshot fcs : filterChains) {
            if (fcs.routeSnapshot() != null) {
                return fcs.routeSnapshot();
            }
        }
        if (defaultFilterChain != null) {
            return defaultFilterChain.routeSnapshot();
        }
        return null;
    }

    /**
     * The resolved filter chain snapshots, in the same order as the
     * {@link Listener}'s {@code filter_chains} list.
     */
    public List<FilterChainSnapshot> filterChains() {
        return filterChains;
    }

    /**
     * The resolved default filter chain snapshot,
     * or {@code null} if no default filter chain is configured.
     */
    @Nullable
    public FilterChainSnapshot defaultFilterChain() {
        return defaultFilterChain;
    }

    /**
     * Returns the first {@link FilterChainSnapshot} that matches the given connection context,
     * or the default filter chain if no explicit chain matches.
     */
    @Nullable
    public FilterChainSnapshot matchFilterChain(ConnectionContext ctx) {
        requireNonNull(ctx, "ctx");
        // Simple: use first filter chain or default. Full matching deferred to follow-up PR.
        if (!filterChains.isEmpty()) {
            return filterChains.get(0);
        }
        return defaultFilterChain;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final ListenerSnapshot that = (ListenerSnapshot) object;
        return Objects.equal(listenerXdsResource, that.listenerXdsResource) &&
               Objects.equal(apiListenerRoute, that.apiListenerRoute) &&
               Objects.equal(filterChains, that.filterChains) &&
               Objects.equal(defaultFilterChain, that.defaultFilterChain);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(listenerXdsResource, apiListenerRoute,
                                filterChains, defaultFilterChain);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("listenerXdsResource", listenerXdsResource)
                          .add("apiListenerRoute", apiListenerRoute)
                          .add("filterChains", filterChains)
                          .add("defaultFilterChain", defaultFilterChain)
                          .toString();
    }

    @Override
    public String toDebugString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("listener", listenerXdsResource.resource())
                          .add("apiListenerRoute",
                               SnapshotUtil.debugString(apiListenerRoute, RouteSnapshot::toDebugString))
                          .add("filterChains",
                               SnapshotUtil.debugStrings(filterChains,
                                                         FilterChainSnapshot::toDebugString))
                          .add("defaultFilterChain",
                               SnapshotUtil.debugString(defaultFilterChain,
                                                        FilterChainSnapshot::toDebugString))
                          .toString();
    }
}
