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

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterXdsResource;
import com.linecorp.armeria.xds.EndpointSnapshot;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig.ZoneAwareLbConfig;

final class PrioritySet {

    private final Map<Integer, HostSet> hostSets;
    private final SortedSet<Integer> priorities;
    private final List<Endpoint> origEndpoints;
    private final ClusterXdsResource clusterXdsResource;
    private final EndpointSnapshot endpointSnapshot;
    private final int panicThreshold;

    PrioritySet(ClusterXdsResource clusterXdsResource,
                EndpointSnapshot endpointSnapshot, Map<Integer, HostSet> hostSets,
                List<Endpoint> origEndpoints) {
        this.clusterXdsResource = clusterXdsResource;
        this.endpointSnapshot = endpointSnapshot;
        panicThreshold = EndpointUtil.panicThreshold(clusterXdsResource.resource());
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

    long minClusterSize() {
        final CommonLbConfig commonLbConfig = commonLbConfig();
        if (commonLbConfig == null ||
            !commonLbConfig.hasZoneAwareLbConfig() ||
            !commonLbConfig.getZoneAwareLbConfig().hasMinClusterSize()) {
            return 6;
        }
        final ZoneAwareLbConfig zoneAwareLbConfig =
                clusterXdsResource.resource().getCommonLbConfig().getZoneAwareLbConfig();
        return zoneAwareLbConfig.getMinClusterSize().getValue();
    }

    long routingEnabled() {
        final CommonLbConfig commonLbConfig = commonLbConfig();
        if (commonLbConfig == null || !commonLbConfig.hasZoneAwareLbConfig()) {
            return 100;
        }
        final ZoneAwareLbConfig zoneAwareLbConfig = commonLbConfig.getZoneAwareLbConfig();
        if (!zoneAwareLbConfig.hasRoutingEnabled()) {
            return 100;
        }
        return Math.round(zoneAwareLbConfig.getRoutingEnabled().getValue());
    }

    @Nullable
    private CommonLbConfig commonLbConfig() {
        if (!clusterXdsResource.resource().hasCommonLbConfig()) {
            return null;
        }
        return clusterXdsResource.resource().getCommonLbConfig();
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

    Cluster cluster() {
        return clusterXdsResource.resource();
    }

    ClusterXdsResource clusterXdsResource() {
        return clusterXdsResource;
    }

    EndpointSnapshot endpointSnapshot() {
        return endpointSnapshot;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("hostSets", hostSets)
                          .add("clusterXdsResource", clusterXdsResource)
                          .add("endpointSnapshot", endpointSnapshot)
                          .toString();
    }

    static final class PrioritySetBuilder {

        private final ImmutableMap.Builder<Integer, HostSet> hostSetsBuilder = ImmutableMap.builder();
        private final ClusterXdsResource clusterXdsResource;
        private final EndpointSnapshot endpointSnapshot;
        private final List<Endpoint> origEndpoints;

        PrioritySetBuilder(ClusterXdsResource clusterXdsResource,
                           EndpointSnapshot endpointSnapshot, List<Endpoint> origEndpoints) {
            this.clusterXdsResource = clusterXdsResource;
            this.endpointSnapshot = endpointSnapshot;
            this.origEndpoints = origEndpoints;
        }

        void createHostSet(int priority, UpdateHostsParam params) {
            final DefaultHostSet hostSet = new DefaultHostSet(params, endpointSnapshot);
            hostSetsBuilder.put(priority, hostSet);
        }

        PrioritySet build() {
            return new PrioritySet(clusterXdsResource, endpointSnapshot,
                                   hostSetsBuilder.build(), origEndpoints);
        }
    }
}
