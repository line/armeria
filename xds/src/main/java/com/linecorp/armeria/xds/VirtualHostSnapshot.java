/*
 * Copyright 2025 LINE Corporation
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
import java.util.Objects;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

/**
 * A snapshot of a {@link VirtualHost}.
 */
public final class VirtualHostSnapshot implements Snapshot<VirtualHostXdsResource> {

    private final VirtualHostXdsResource virtualHostXdsResource;
    private final List<RouteEntry> routeEntries;
    private final int index;

    VirtualHostSnapshot(VirtualHostXdsResource virtualHostXdsResource,
                        List<@Nullable ClusterSnapshot> clusterSnapshots, int index) {
        this.virtualHostXdsResource = virtualHostXdsResource;
        assert clusterSnapshots.size() == virtualHostXdsResource.resource().getRoutesCount();

        final ImmutableList.Builder<RouteEntry> routeEntriesBuilder = ImmutableList.builder();
        for (int i = 0; i < clusterSnapshots.size(); i++) {
            final ClusterSnapshot clusterSnapshot = clusterSnapshots.get(i);
            final Route route = virtualHostXdsResource.resource().getRoutes(i);
            routeEntriesBuilder.add(new RouteEntry(route, clusterSnapshot));
        }
        routeEntries = routeEntriesBuilder.build();
        this.index = index;
    }

    @Override
    public VirtualHostXdsResource xdsResource() {
        return virtualHostXdsResource;
    }

    /**
     * A list of routes corresponding to {@link VirtualHost#getRoutesList()}.
     */
    public List<RouteEntry> routeEntries() {
        return routeEntries;
    }

    int index() {
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
        final VirtualHostSnapshot that = (VirtualHostSnapshot) o;
        return index == that.index &&
               Objects.equals(virtualHostXdsResource, that.virtualHostXdsResource) &&
               Objects.equals(routeEntries, that.routeEntries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(virtualHostXdsResource, routeEntries, index);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("virtualHostXdsResource", virtualHostXdsResource)
                          .add("routeEntries", routeEntries)
                          .toString();
    }
}
