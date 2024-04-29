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
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.EndpointSnapshot;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

final class ClusterEntry implements Consumer<List<Endpoint>>, AsyncCloseable {

    private final EndpointGroup endpointGroup;
    private final Cluster cluster;
    private final ClusterLoadAssignment clusterLoadAssignment;
    private final LoadBalancer loadBalancer;
    private List<Endpoint> endpoints = ImmutableList.of();

    ClusterEntry(ClusterSnapshot clusterSnapshot, ClusterManager clusterManager) {
        final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
        assert endpointSnapshot != null;
        cluster = clusterSnapshot.xdsResource().resource();
        clusterLoadAssignment = endpointSnapshot.xdsResource().resource();
        if (cluster.hasLbSubsetConfig()) {
            loadBalancer = new SubsetLoadBalancer(clusterSnapshot);
        } else {
            loadBalancer = new ZoneAwareLoadBalancer();
        }

        // The order of adding listeners is important
        endpointGroup = XdsEndpointUtil.convertEndpointGroup(clusterSnapshot);
        endpointGroup.addListener(this, true);
        endpointGroup.addListener(clusterManager, true);
    }

    @Nullable
    Endpoint selectNow(ClientRequestContext ctx) {
        return loadBalancer.selectNow(ctx);
    }

    @Override
    public void accept(List<Endpoint> endpoints) {
        this.endpoints = ImmutableList.copyOf(endpoints);
        final PriorityStateManager priorityStateManager =
                new PriorityStateManager(cluster, clusterLoadAssignment);
        for (Endpoint endpoint: endpoints) {
            priorityStateManager.registerEndpoint(endpoint);
        }
        final PrioritySet prioritySet = priorityStateManager.build();
        loadBalancer.prioritySetUpdated(prioritySet);
    }

    List<Endpoint> allEndpoints() {
        return endpoints;
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return endpointGroup.closeAsync();
    }

    @Override
    public void close() {
        endpointGroup.close();
    }
}
