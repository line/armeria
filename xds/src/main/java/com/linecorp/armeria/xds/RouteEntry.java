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

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;

/**
 * Represents a {@link Route}.
 */
public final class RouteEntry {

    private final Route route;
    @Nullable
    private final ClusterSnapshot clusterSnapshot;

    RouteEntry(Route route, @Nullable ClusterSnapshot clusterSnapshot) {
        this.route = route;
        this.clusterSnapshot = clusterSnapshot;
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
               Objects.equals(clusterSnapshot, that.clusterSnapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(route, clusterSnapshot);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("route", route)
                          .add("clusterSnapshot", clusterSnapshot)
                          .toString();
    }
}
