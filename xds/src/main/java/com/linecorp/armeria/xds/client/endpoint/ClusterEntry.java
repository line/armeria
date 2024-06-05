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

import static com.linecorp.armeria.internal.common.util.CollectionUtil.truncate;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.EndpointSnapshot;

import io.netty.util.concurrent.EventExecutor;

final class ClusterEntry implements AsyncCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ClusterEntry.class);

    private final EndpointsPool endpointsPool;
    @Nullable
    private volatile LoadBalancer loadBalancer;
    private final ClusterManager clusterManager;
    private final EventExecutor eventExecutor;
    private List<Endpoint> endpoints = ImmutableList.of();

    ClusterEntry(ClusterSnapshot clusterSnapshot,
                 ClusterManager clusterManager, EventExecutor eventExecutor) {
        this.clusterManager = clusterManager;
        this.eventExecutor = eventExecutor;
        endpointsPool = new EndpointsPool(eventExecutor);
        updateClusterSnapshot(clusterSnapshot);
    }

    @Nullable
    Endpoint selectNow(ClientRequestContext ctx) {
        final LoadBalancer loadBalancer = this.loadBalancer;
        if (loadBalancer == null) {
            return null;
        }
        return loadBalancer.selectNow(ctx);
    }

    void updateClusterSnapshot(ClusterSnapshot clusterSnapshot) {
        final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
        assert endpointSnapshot != null;
        endpointsPool.updateClusterSnapshot(clusterSnapshot, endpoints -> {
            accept(clusterSnapshot, endpoints);
        });
    }

    void accept(ClusterSnapshot clusterSnapshot, List<Endpoint> endpoints) {
        assert eventExecutor.inEventLoop();
        this.endpoints = ImmutableList.copyOf(endpoints);
        final PrioritySet prioritySet = new PriorityStateManager(clusterSnapshot, endpoints).build();
        if (logger.isTraceEnabled()) {
            logger.trace("XdsEndpointGroup is using a new PrioritySet({})", prioritySet);
        }
        if (clusterSnapshot.xdsResource().resource().hasLbSubsetConfig()) {
            loadBalancer = new SubsetLoadBalancer(prioritySet);
        } else {
            loadBalancer = new DefaultLoadBalancer(prioritySet);
        }
        clusterManager.notifyListeners();
    }

    List<Endpoint> allEndpoints() {
        return endpoints;
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return endpointsPool.closeAsync();
    }

    @Override
    public void close() {
        endpointsPool.close();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("endpointsPool", endpointsPool)
                          .add("loadBalancer", loadBalancer)
                          .add("numEndpoints", endpoints.size())
                          .add("endpoints", truncate(endpoints, 10))
                          .toString();
    }
}
