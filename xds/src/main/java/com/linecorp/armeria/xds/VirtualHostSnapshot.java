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

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * A snapshot of a {@link VirtualHost}.
 */
public final class VirtualHostSnapshot implements Snapshot<VirtualHostXdsResource> {

    private final VirtualHostXdsResource virtualHostXdsResource;
    private final Map<String, ParsedFilterConfig> filterConfigs;
    private final List<RouteEntry> routeEntries;
    private final int index;

    VirtualHostSnapshot(VirtualHostXdsResource virtualHostXdsResource,
                        Map<String, ClusterSnapshot> clusterSnapshots, int index) {
        this.virtualHostXdsResource = virtualHostXdsResource;
        final VirtualHost virtualHost = virtualHostXdsResource.resource();
        filterConfigs = FilterUtil.toParsedFilterConfigs(virtualHost.getTypedPerFilterConfigMap());

        final ImmutableList.Builder<RouteEntry> routeEntriesBuilder = ImmutableList.builder();
        final List<Route> routes = virtualHost.getRoutesList();
        for (int i = 0; i < routes.size(); i++) {
            final Route route = routes.get(i);
            ClusterSnapshot clusterSnapshot = null;
            if (route.getRoute().hasCluster()) {
                clusterSnapshot = clusterSnapshots.get(route.getRoute().getCluster());
            }
            routeEntriesBuilder.add(new RouteEntry(route, clusterSnapshot, i));
        }
        routeEntries = routeEntriesBuilder.build();
        this.index = index;
    }

    VirtualHostSnapshot(VirtualHostXdsResource virtualHostXdsResource,
                        List<RouteEntry> routeEntries, Map<String, ParsedFilterConfig> filterConfigs,
                        int index) {
        this.virtualHostXdsResource = virtualHostXdsResource;
        this.routeEntries = routeEntries;
        this.filterConfigs = filterConfigs;
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

    /**
     * The index of this route within a {@link RouteConfiguration}.
     */
    public int index() {
        return index;
    }

    VirtualHostSnapshot withFilterConfigs(Map<String, ParsedFilterConfig> parentFilterConfigs,
                                          List<HttpFilter> upstreamFilters) {
        final Map<String, ParsedFilterConfig> mergedFilterConfigs =
                FilterUtil.mergeFilterConfigs(parentFilterConfigs, filterConfigs);
        final List<RouteEntry> routeEntries =
                this.routeEntries.stream().map(routeEntry -> routeEntry.withFilterConfigs(mergedFilterConfigs,
                                                                                          upstreamFilters))
                                 .collect(ImmutableList.toImmutableList());
        return new VirtualHostSnapshot(virtualHostXdsResource, routeEntries, mergedFilterConfigs, index);
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
