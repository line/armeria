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

import static com.linecorp.armeria.xds.FilterUtil.toParsedFilterConfigs;

import java.util.List;
import java.util.Map;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * A snapshot of a {@link RouteConfiguration} resource.
 */
@UnstableApi
public final class RouteSnapshot implements Snapshot<RouteXdsResource> {

    private final RouteXdsResource routeXdsResource;
    private final List<VirtualHostSnapshot> virtualHostSnapshots;
    private final Map<String, ParsedFilterConfig> filterConfigs;

    RouteSnapshot(RouteXdsResource routeXdsResource, List<VirtualHostSnapshot> virtualHostSnapshots) {
        this.routeXdsResource = routeXdsResource;
        filterConfigs = toParsedFilterConfigs(routeXdsResource.resource().getTypedPerFilterConfigMap());
        this.virtualHostSnapshots =
                virtualHostSnapshots.stream().map(vhs -> vhs.withFilterConfigs(filterConfigs,
                                                                               ImmutableList.of()))
                                    .collect(ImmutableList.toImmutableList());
    }

    RouteSnapshot(RouteXdsResource routeXdsResource, List<HttpFilter> upstreamFilters,
                  Map<String, ParsedFilterConfig> filterConfigs,
                  List<VirtualHostSnapshot> virtualHostSnapshots) {
        this.routeXdsResource = routeXdsResource;
        this.filterConfigs = filterConfigs;
        this.virtualHostSnapshots =
                virtualHostSnapshots.stream().map(vhs -> vhs.withFilterConfigs(filterConfigs, upstreamFilters))
                                    .collect(ImmutableList.toImmutableList());
    }

    RouteSnapshot withRouter(Router router) {
        return new RouteSnapshot(routeXdsResource, router.getUpstreamHttpFiltersList(),
                                 filterConfigs, virtualHostSnapshots);
    }

    @Override
    public RouteXdsResource xdsResource() {
        return routeXdsResource;
    }

    /**
     * The virtual hosts represented by {@link RouteConfiguration#getVirtualHostsList()}.
     */
    public List<VirtualHostSnapshot> virtualHostSnapshots() {
        return virtualHostSnapshots;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final RouteSnapshot that = (RouteSnapshot) object;
        return Objects.equal(routeXdsResource, that.routeXdsResource) &&
               Objects.equal(virtualHostSnapshots, that.virtualHostSnapshots);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(routeXdsResource, virtualHostSnapshots);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("routeXdsResource", routeXdsResource)
                          .add("virtualHostSnapshots", virtualHostSnapshots)
                          .toString();
    }
}
