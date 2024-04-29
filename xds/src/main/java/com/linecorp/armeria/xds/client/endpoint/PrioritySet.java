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

import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

final class PrioritySet {
    private final Map<Integer, HostSet> hostSets;
    private final SortedSet<Integer> priorities;
    private final Cluster cluster;
    private final ClusterLoadAssignment clusterLoadAssignment;
    private final int panicThreshold;

    PrioritySet(Cluster cluster, ClusterLoadAssignment clusterLoadAssignment,
                Map<Integer, HostSet> hostSets) {
        this.cluster = cluster;
        this.clusterLoadAssignment = clusterLoadAssignment;
        panicThreshold = EndpointUtil.panicThreshold(cluster);
        this.hostSets = hostSets;
        priorities = new TreeSet<>(hostSets.keySet());
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

    static final class PrioritySetBuilder {

        private final ImmutableMap.Builder<Integer, HostSet> hostSetsBuilder = ImmutableMap.builder();
        private final Cluster cluster;
        private final ClusterLoadAssignment clusterLoadAssignment;

        PrioritySetBuilder(PrioritySet prioritySet) {
            cluster = prioritySet.cluster;
            clusterLoadAssignment = prioritySet.clusterLoadAssignment;
        }

        PrioritySetBuilder(Cluster cluster, ClusterLoadAssignment clusterLoadAssignment) {
            this.cluster = cluster;
            this.clusterLoadAssignment = clusterLoadAssignment;
        }

        void createHostSet(int priority, UpdateHostsParam params) {
            final HostSet hostSet = new HostSet(params, clusterLoadAssignment);
            hostSetsBuilder.put(priority, hostSet);
        }

        PrioritySet build() {
            return new PrioritySet(cluster, clusterLoadAssignment, hostSetsBuilder.build());
        }
    }
}
