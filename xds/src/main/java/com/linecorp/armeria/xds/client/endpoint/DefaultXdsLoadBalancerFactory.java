/*
 * Copyright 2025 LINE Corporation
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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterXdsResource;
import com.linecorp.armeria.xds.EndpointSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.TransportSocketMatchSnapshot;
import com.linecorp.armeria.xds.TransportSocketSnapshot;

import io.envoyproxy.envoy.config.core.v3.Locality;
import io.netty.util.concurrent.EventExecutor;

final class DefaultXdsLoadBalancerFactory implements XdsLoadBalancerFactory {

    @Nullable
    private XdsLoadBalancer delegate;
    private final EventExecutor eventLoop;
    private final Locality locality;
    private final XdsLoadBalancerLifecycleObserver observer;

    private final AttributesPool attributesPool = new AttributesPool();
    private EndpointGroup endpointGroup = EndpointGroup.of();

    DefaultXdsLoadBalancerFactory(EventExecutor eventLoop, Locality locality,
                                  XdsLoadBalancerLifecycleObserver observer) {
        this.eventLoop = eventLoop;
        this.locality = locality;
        this.observer = observer;
    }

    @Override
    public void register(ClusterXdsResource clusterXdsResource, EndpointSnapshot endpointSnapshot,
                         TransportSocketSnapshot transportSocket,
                         List<TransportSocketMatchSnapshot> transportSocketMatches,
                         SnapshotWatcher<XdsLoadBalancer> watcher,
                         @Nullable XdsLoadBalancer localLoadBalancer) {
        assert eventLoop.inEventLoop();
        endpointGroup.closeAsync();

        observer.resourceUpdated(clusterXdsResource);
        endpointGroup = XdsEndpointUtil.convertEndpointGroup(clusterXdsResource, endpointSnapshot,
                                                             transportSocket, transportSocketMatches);
        final EndpointGroup currentGroup = endpointGroup;
        endpointGroup.addListener(endpoints -> eventLoop.execute(() -> {
            if (currentGroup != endpointGroup) {
                return;
            }
            updateEndpoints(clusterXdsResource, endpointSnapshot, endpoints, watcher, localLoadBalancer);
        }), true);
    }

    private void updateEndpoints(ClusterXdsResource clusterXdsResource,
                                 EndpointSnapshot endpointSnapshot,
                                 List<Endpoint> endpoints,
                                 SnapshotWatcher<XdsLoadBalancer> watcher,
                                 @Nullable XdsLoadBalancer localLoadBalancer) {
        endpoints = attributesPool.cacheAttributesAndDelegate(endpoints);
        observer.endpointsUpdated(clusterXdsResource, endpoints);
        try {
            final PrioritySet prioritySet =
                    new PriorityStateManager(clusterXdsResource, endpointSnapshot, endpoints).build();

            XdsLoadBalancer loadBalancer = new DefaultLoadBalancer(prioritySet, locality, localLoadBalancer);
            if (clusterXdsResource.resource().hasLbSubsetConfig()) {
                loadBalancer = new SubsetLoadBalancer(prioritySet, loadBalancer, locality,
                                                      localLoadBalancer);
            }
            delegate = loadBalancer;
            observer.stateUpdated(clusterXdsResource, loadBalancer);
            watcher.onUpdate(loadBalancer, null);
        } catch (Exception e) {
            observer.stateRejected(clusterXdsResource, endpoints, e);
            watcher.onUpdate(null, e);
        }
    }

    @Override
    public void close() {
        endpointGroup.closeAsync();
        observer.close();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("delegate", delegate)
                          .toString();
    }
}
