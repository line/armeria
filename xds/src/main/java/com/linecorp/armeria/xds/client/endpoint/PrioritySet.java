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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

final class PrioritySet {
    private final Map<Integer, HostSet> hostSets;
    private final SortedSet<Integer> priorities;
    private final List<Endpoint> origEndpoints;
    private final Cluster cluster;
    private final int panicThreshold;

    PrioritySet(Cluster cluster, Map<Integer, HostSet> hostSets, List<Endpoint> origEndpoints) {
        this.cluster = cluster;
        panicThreshold = EndpointUtil.panicThreshold(cluster);
        this.hostSets = hostSets;
        priorities = new TreeSet<>(hostSets.keySet());
        this.origEndpoints = origEndpoints;
    }

    boolean failTrafficOnPanic() {
        final CommonLbConfig commonLbConfig = commonLbConfig();
        if (commonLbConfig == null) {
            return false;
        }
        if (!commonLbConfig.hasZoneAwareLbConfig()) {
            return false;
        }
        return commonLbConfig.getZoneAwareLbConfig().getFailTrafficOnPanic();
    }

    @Nullable
    private CommonLbConfig commonLbConfig() {
        if (!cluster.hasCommonLbConfig()) {
            return null;
        }
        return cluster.getCommonLbConfig();
    }

    boolean localityWeightedBalancing() {
        final CommonLbConfig commonLbConfig = commonLbConfig();
        if (commonLbConfig == null) {
            return false;
        }
        return commonLbConfig.hasLocalityWeightedLbConfig();
    }

    int panicThreshold() {
        return panicThreshold;
    }

    SortedSet<Integer> priorities() {
        return priorities;
    }

    Map<Integer, HostSet> hostSets() {
        return hostSets;
    }

    /**
     * Returns the original list of endpoints this priority set was created with.
     * This method acts as a temporary measure to keep backwards compatibility with
     * {@link SubsetLoadBalancer}. It will be removed once {@link SubsetLoadBalancer}
     * is fully implemented.
     */
    List<Endpoint> endpoints() {
        return origEndpoints;
    }

    static final class PrioritySetBuilder {

        private final ImmutableMap.Builder<Integer, HostSet> hostSetsBuilder = ImmutableMap.builder();
        private final Cluster cluster;
        private final ClusterLoadAssignment clusterLoadAssignment;
        private final List<Endpoint> origEndpoints;

        PrioritySetBuilder(Cluster cluster, ClusterLoadAssignment clusterLoadAssignment,
                           List<Endpoint> origEndpoints) {
            this.cluster = cluster;
            this.clusterLoadAssignment = clusterLoadAssignment;
            this.origEndpoints = origEndpoints;
        }

        void createHostSet(int priority, UpdateHostsParam params) {
            final HostSet hostSet = new HostSet(params, clusterLoadAssignment);
            hostSetsBuilder.put(priority, hostSet);
        }

        PrioritySet build() {
            return new PrioritySet(cluster, hostSetsBuilder.build(), origEndpoints);
        }
    }
}
