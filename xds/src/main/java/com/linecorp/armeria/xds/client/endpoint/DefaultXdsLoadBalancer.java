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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractListenable;
import com.linecorp.armeria.internal.client.AbstractAsyncSelector;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.envoyproxy.envoy.config.core.v3.Locality;
import io.netty.util.concurrent.EventExecutor;

final class DefaultXdsLoadBalancer implements UpdatableXdsLoadBalancer {

    private Consumer<List<Endpoint>> updateEndpointsCallback = endpoints -> {};

    @Nullable
    private LoadBalancer delegate;
    private final EventExecutor eventLoop;
    private final XdsLoadBalancerLifecycleObserver observer;

    @Nullable
    private ClusterSnapshot clusterSnapshot;
    @Nullable
    private final LocalCluster localCluster;

    @Nullable
    private List<Endpoint> endpoints;
    @Nullable
    private PrioritySet localPrioritySet;
    private final AttributesPool attributesPool = new AttributesPool();
    private EndpointGroup endpointGroup = EndpointGroup.of();
    @Nullable
    private PrioritySet prioritySet;
    private final LoadBalancerEndpointSelector endpointSelector = new LoadBalancerEndpointSelector();
    private final PrioritySetListener prioritySetListener = new PrioritySetListener();

    private final EndpointsWatchers endpointsWatchers = new EndpointsWatchers();

    DefaultXdsLoadBalancer(EventExecutor eventLoop, Locality locality,
                           @Nullable XdsLoadBalancer localLoadBalancer,
                           XdsLoadBalancerLifecycleObserver observer) {
        this.eventLoop = eventLoop;
        this.observer = observer;
        if (localLoadBalancer != null) {
            localCluster = new LocalCluster(locality, localLoadBalancer);
            localCluster.localLoadBalancer().prioritySetListener()
                        .addListener(this::updateLocalLoadBalancer, true);
        } else {
            localCluster = null;
        }
    }

    @Override
    public void updateSnapshot(ClusterSnapshot clusterSnapshot) {
        if (Objects.equals(clusterSnapshot, this.clusterSnapshot)) {
            return;
        }

        // First remove the listener for the previous endpointGroup and close it
        endpointGroup.removeListener(updateEndpointsCallback);
        endpointGroup.closeAsync();

        observer.resourceUpdated(clusterSnapshot);
        // Set the new clusterSnapshot
        endpointGroup = XdsEndpointUtil.convertEndpointGroup(clusterSnapshot);
        updateEndpointsCallback =
                endpoints0 -> eventLoop.execute(() -> updateEndpoints(clusterSnapshot, endpoints0));
        endpointGroup.addListener(updateEndpointsCallback, true);
    }

    private void updateEndpoints(ClusterSnapshot clusterSnapshot, List<Endpoint> endpoints) {
        endpoints = attributesPool.cacheAttributesAndDelegate(endpoints);
        this.endpoints = endpoints;
        this.clusterSnapshot = clusterSnapshot;
        observer.endpointsUpdated(clusterSnapshot, endpoints);
        tryRefresh(clusterSnapshot, endpoints);
    }

    private void updateLocalLoadBalancer(PrioritySet localPrioritySet) {
        this.localPrioritySet = localPrioritySet;
        tryRefresh(clusterSnapshot, endpoints);
    }

    private void tryRefresh(@Nullable ClusterSnapshot clusterSnapshot, @Nullable List<Endpoint> endpoints) {
        if (endpoints == null || clusterSnapshot == null) {
            return;
        }
        try {
            final PrioritySet prioritySet = new PriorityStateManager(clusterSnapshot, endpoints).build();

            final PrioritySet localPrioritySet = this.localPrioritySet;
            LoadBalancer loadBalancer = new DefaultLoadBalancer(prioritySet, localCluster, localPrioritySet);
            if (clusterSnapshot.xdsResource().resource().hasLbSubsetConfig()) {
                loadBalancer = new SubsetLoadBalancer(prioritySet, loadBalancer, localCluster,
                                                      localPrioritySet);
            }
            delegate = loadBalancer;
            this.prioritySet = prioritySet;

            endpointSelector.refresh();
            prioritySetListener.notifyListeners0(prioritySet);
            endpointsWatchers.notifyListeners0(prioritySet.endpoints());
            observer.stateUpdated(clusterSnapshot, loadBalancer);
        } catch (Exception e) {
            observer.stateRejected(clusterSnapshot, endpoints, e);
        }
    }

    @Nullable
    @Override
    public Endpoint selectNow(ClientRequestContext ctx) {
        if (delegate == null) {
            return null;
        }
        return delegate.selectNow(ctx);
    }

    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                              ScheduledExecutorService executor, long selectionTimeoutMillis) {
        return endpointSelector.select(ctx, executor, selectionTimeoutMillis);
    }

    private final class LoadBalancerEndpointSelector extends AbstractAsyncSelector<Endpoint> {

        @Override
        protected void onTimeout(ClientRequestContext ctx, long selectionTimeoutMillis) {

            final PrioritySet prioritySet = DefaultXdsLoadBalancer.this.prioritySet;
            final TimeoutException timeoutException;
            if (prioritySet != null) {
                timeoutException = new TimeoutException(
                        "Failed to select an endpoint from state '" + clusterSnapshot +
                        "' within '" + selectionTimeoutMillis + "'ms");
            } else {
                timeoutException = new TimeoutException(
                        "Failed to retrieve endpoints from '" + endpointGroup +
                        "' for snapshot '" + clusterSnapshot + "' within '" + selectionTimeoutMillis + "'ms");
            }
            ctx.cancel(timeoutException);
            super.onTimeout(ctx, selectionTimeoutMillis);
        }

        @Nullable
        @Override
        public Endpoint selectNow(ClientRequestContext ctx) {
            return DefaultXdsLoadBalancer.this.selectNow(ctx);
        }
    }

    @Override
    public void close() {
        // wait for an arbitrary amount of seconds just in case:
        // 1) this cluster uses DNS type discovery
        // 2) this load balancer was closed immediately after creation (the cluster was removed immediately)
        // 3) a request selected this load balancer
        // if the endpointGroup is closed immediately, requests may fail
        // before there is a chance of dns resolution
        eventLoop.schedule(() -> {
            endpointGroup.removeListener(updateEndpointsCallback);
            endpointGroup.close();
        }, 10, TimeUnit.SECONDS);
        observer.close();
    }

    PrioritySetListener prioritySetListener() {
        return prioritySetListener;
    }

    final class PrioritySetListener extends AbstractListenable<PrioritySet> {

        void notifyListeners0(PrioritySet prioritySet) {
            notifyListeners(prioritySet);
        }

        @Nullable
        @Override
        protected PrioritySet latestValue() {
            return prioritySet;
        }
    }

    // TODO: remove once XdsEndpointGroup is removed
    @Override
    public void addEndpointsListener(Consumer<? super List<Endpoint>> listener) {
        endpointsWatchers.addListener(listener, true);
    }

    @Override
    public void removeEndpointsListener(Consumer<? super List<Endpoint>> listener) {
        endpointsWatchers.removeListener(listener);
    }

    private static class EndpointsWatchers extends AbstractListenable<List<Endpoint>> {

        @Nullable
        private List<Endpoint> endpoints;

        private void notifyListeners0(List<Endpoint> endpoints) {
            this.endpoints = endpoints;
            notifyListeners(endpoints);
        }

        @Override
        @Nullable
        protected List<Endpoint> latestValue() {
            return endpoints;
        }
    }
}
