/*
 * Copyright 2025 LY Corporation
 *
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

package com.linecorp.armeria.xds;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;

/**
 * A snapshot of a single filter chain, containing match criteria, the resolved
 * transport socket, and route snapshot.
 */
@UnstableApi
public final class FilterChainSnapshot implements Snapshot<FilterChainMatch> {

    private final FilterChainMatch filterChainMatch;
    private final TransportSocketSnapshot transportSocketSnapshot;
    @Nullable
    private final RouteSnapshot routeSnapshot;

    FilterChainSnapshot(FilterChainMatch filterChainMatch,
                        TransportSocketSnapshot transportSocketSnapshot,
                        @Nullable RouteSnapshot routeSnapshot) {
        this.filterChainMatch = filterChainMatch;
        this.transportSocketSnapshot = transportSocketSnapshot;
        this.routeSnapshot = routeSnapshot;
    }

    /**
     * Returns the {@link FilterChainMatch} criteria for this chain.
     */
    public FilterChainMatch filterChainMatch() {
        return filterChainMatch;
    }

    /**
     * Returns the resolved transport socket snapshot for this filter chain.
     */
    public TransportSocketSnapshot transportSocketSnapshot() {
        return transportSocketSnapshot;
    }

    /**
     * Returns the resolved route snapshot for this filter chain,
     * or {@code null} if the filter chain's HCM does not configure routes.
     */
    @Nullable
    public RouteSnapshot routeSnapshot() {
        return routeSnapshot;
    }

    @Override
    public FilterChainMatch xdsResource() {
        return filterChainMatch;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final FilterChainSnapshot that = (FilterChainSnapshot) object;
        return Objects.equal(filterChainMatch, that.filterChainMatch) &&
               Objects.equal(transportSocketSnapshot, that.transportSocketSnapshot) &&
               Objects.equal(routeSnapshot, that.routeSnapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(filterChainMatch, transportSocketSnapshot, routeSnapshot);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("transportSocketSnapshot", transportSocketSnapshot)
                          .add("routeSnapshot", routeSnapshot)
                          .toString();
    }

    @Override
    public String toDebugString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("filterChainMatch", filterChainMatch)
                          .add("transportSocketSnapshot", transportSocketSnapshot.toDebugString())
                          .add("routeSnapshot",
                               SnapshotUtil.debugString(routeSnapshot, RouteSnapshot::toDebugString))
                          .toString();
    }
}
