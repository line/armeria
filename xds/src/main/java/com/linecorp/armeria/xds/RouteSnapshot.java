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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

/**
 * A snapshot of a {@link RouteConfiguration} resource.
 */
@UnstableApi
public final class RouteSnapshot implements Snapshot<RouteXdsResource> {

    private final RouteXdsResource routeXdsResource;
    private final List<ClusterSnapshot> clusterSnapshots;

    private final Map<VirtualHost, List<ClusterSnapshot>> virtualHostMap;

    RouteSnapshot(RouteXdsResource routeXdsResource, List<ClusterSnapshot> clusterSnapshots) {
        this.routeXdsResource = routeXdsResource;
        this.clusterSnapshots = clusterSnapshots;

        final LinkedHashMap<VirtualHost, List<ClusterSnapshot>> virtualHostMap = new LinkedHashMap<>();
        for (ClusterSnapshot clusterSnapshot: clusterSnapshots) {
            final VirtualHost virtualHost = clusterSnapshot.virtualHost();
            assert virtualHost != null;
            virtualHostMap.computeIfAbsent(virtualHost, ignored -> new ArrayList<>())
                          .add(clusterSnapshot);
        }
        this.virtualHostMap = Collections.unmodifiableMap(virtualHostMap);
    }

    @Override
    public RouteXdsResource xdsResource() {
        return routeXdsResource;
    }

    /**
     * A list of {@link ClusterSnapshot}s which belong to this {@link RouteConfiguration}.
     */
    public List<ClusterSnapshot> clusterSnapshots() {
        return clusterSnapshots;
    }

    /**
     * A map of {@link VirtualHost}s to {@link ClusterSnapshot}s which belong
     * to this {@link RouteConfiguration}.
     */
    public Map<VirtualHost, List<ClusterSnapshot>> virtualHostMap() {
        return virtualHostMap;
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
               Objects.equal(clusterSnapshots, that.clusterSnapshots);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(routeXdsResource, clusterSnapshots);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("routeXdsResource", routeXdsResource)
                          .add("clusterSnapshots", clusterSnapshots)
                          .toString();
    }
}
