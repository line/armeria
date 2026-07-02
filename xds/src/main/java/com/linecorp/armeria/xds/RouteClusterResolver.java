/*
 * Copyright 2026 LY Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.loadbalancer.LoadBalancer;
import com.linecorp.armeria.common.loadbalancer.SimpleLoadBalancer;

final class RouteClusterResolver {

    private static final RouteClusterResolver EMPTY = new RouteClusterResolver(null, null, null);

    @Nullable
    private final RouteCluster singleCluster;
    @Nullable
    private final List<WeightedClusterSnapshot> weightedClusters;
    @Nullable
    private final SimpleLoadBalancer<WeightedClusterSnapshot> loadBalancer;

    private RouteClusterResolver(@Nullable RouteCluster singleCluster,
                            @Nullable List<WeightedClusterSnapshot> weightedClusters,
                            @Nullable SimpleLoadBalancer<WeightedClusterSnapshot> loadBalancer) {
        this.singleCluster = singleCluster;
        this.weightedClusters = weightedClusters;
        this.loadBalancer = loadBalancer;
    }

    static RouteClusterResolver ofSingle(DefaultRouteCluster routeCluster) {
        requireNonNull(routeCluster, "routeCluster");
        return new RouteClusterResolver(routeCluster, null, null);
    }

    static RouteClusterResolver ofWeighted(List<WeightedClusterSnapshot> weightedClusters) {
        requireNonNull(weightedClusters, "weightedClusters");
        return new RouteClusterResolver(null, weightedClusters,
                                   LoadBalancer.ofWeightedRoundRobin(weightedClusters));
    }

    static RouteClusterResolver empty() {
        return EMPTY;
    }

    @Nullable
    ClusterSnapshot clusterSnapshot() {
        return singleCluster != null ? singleCluster.clusterSnapshot() : null;
    }

    @Nullable
    List<WeightedClusterSnapshot> weightedClusters() {
        return weightedClusters;
    }

    @Nullable
    RouteCluster resolve() {
        if (singleCluster != null) {
            return singleCluster;
        }
        if (loadBalancer != null) {
            return loadBalancer.pick();
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RouteClusterResolver that = (RouteClusterResolver) o;
        return Objects.equals(singleCluster, that.singleCluster) &&
               Objects.equals(weightedClusters, that.weightedClusters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(singleCluster, weightedClusters);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("singleCluster", singleCluster)
                          .add("weightedClusters", weightedClusters)
                          .toString();
    }

    String toDebugString() {
        final MoreObjects.ToStringHelper helper = MoreObjects.toStringHelper(this).omitNullValues();
        helper.add("singleCluster",
                   SnapshotUtil.debugString(singleCluster,
                                            rc -> rc.clusterSnapshot().toDebugString()));
        if (weightedClusters != null) {
            helper.add("weightedClusters",
                        SnapshotUtil.debugStrings(weightedClusters,
                                                  wc -> wc.clusterSnapshot().toDebugString()));
        }
        return helper.toString();
    }
}
