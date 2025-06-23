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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.AbstractEndpointSelector;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.AbstractListenable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.VirtualHostSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * Provides a simple {@link EndpointGroup} which listens to an xDS cluster to select endpoints.
 * Listening to EDS can be done like the following:
 * <pre>{@code
 * XdsBootstrap watchersStorage = XdsBootstrap.of(...);
 * EndpointGroup endpointGroup = XdsEndpointGroup.of(watchersStorage, "my-cluster");
 * WebClient client = WebClient.of(SessionProtocol.HTTP, endpointGroup);
 * }</pre>
 * Currently, all {@link SocketAddress}es of a {@link ClusterLoadAssignment} are aggregated
 * to a list and added to this {@link EndpointGroup}. Features such as automatic TLS detection
 * or locality based load balancing are not supported yet.
 * Note that it is important to shut down the endpoint group to clean up resources
 * for the provided {@link XdsBootstrap}.
 *
 * @deprecated use {@link XdsHttpPreprocessor} or {@link XdsRpcPreprocessor} instead
 */
@UnstableApi
@Deprecated
public final class XdsEndpointGroup extends AbstractListenable<List<Endpoint>>
        implements EndpointGroup, SnapshotWatcher<ListenerSnapshot>, Consumer<List<Endpoint>> {

    /**
     * Creates a {@link XdsEndpointGroup} which listens to the specified listener.
     */
    public static XdsEndpointGroup of(String listenerName, XdsBootstrap xdsBootstrap) {
        return of(listenerName, xdsBootstrap, false);
    }

    /**
     * Creates a {@link XdsEndpointGroup} which listens to the specified listener.
     */
    public static XdsEndpointGroup of(String listenerName, XdsBootstrap xdsBootstrap,
                                      boolean allowEmptyEndpoints) {
        return new XdsEndpointGroup(listenerName, xdsBootstrap, allowEmptyEndpoints);
    }

    /**
     * Creates a {@link XdsEndpointGroup} based on the specified {@link ClusterSnapshot}.
     * This may be useful if one would like to create an {@link EndpointGroup} based on
     * a {@link GrpcService}.
     */
    @UnstableApi
    public static XdsEndpointGroup of(ClusterSnapshot clusterSnapshot) {
        throw new UnsupportedOperationException("Use ClusterSnapshot.loadBalancer() to select endpoints.");
    }

    private static final List<Endpoint> UNINITIALIZED_ENDPOINTS = Collections.unmodifiableList(
            new ArrayList<>());

    private final XdsEndpointSelectionStrategy selectionStrategy;
    private final boolean allowEmptyEndpoints;
    private final Lock stateLock = new ReentrantShortLock();
    private final CompletableFuture<List<Endpoint>> initialEndpointsFuture = new CompletableFuture<>();

    private final EndpointSelector selector;
    @Nullable
    private volatile XdsLoadBalancer loadBalancer;
    private final ListenerRoot listenerRoot;
    private List<Endpoint> endpoints = UNINITIALIZED_ENDPOINTS;

    XdsEndpointGroup(String listenerName, XdsBootstrap xdsBootstrap, boolean allowEmptyEndpoints) {
        this.allowEmptyEndpoints = allowEmptyEndpoints;
        selectionStrategy = new XdsEndpointSelectionStrategy();
        selector = selectionStrategy.newSelector(this);
        listenerRoot = xdsBootstrap.listenerRoot(listenerName);
        listenerRoot.addSnapshotWatcher(this);
    }

    @Override
    public void snapshotUpdated(ListenerSnapshot listenerSnapshot) {
        final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
        if (routeSnapshot == null) {
            return;
        }
        final List<VirtualHostSnapshot> virtualHostSnapshots = routeSnapshot.virtualHostSnapshots();
        if (virtualHostSnapshots.isEmpty()) {
            return;
        }
        final VirtualHostSnapshot virtualHostSnapshot = virtualHostSnapshots.get(0);
        if (virtualHostSnapshot.routeEntries().isEmpty()) {
            return;
        }
        final ClusterSnapshot clusterSnapshot = virtualHostSnapshot.routeEntries().get(0).clusterSnapshot();
        if (clusterSnapshot == null) {
            return;
        }
        final XdsLoadBalancer loadBalancer = clusterSnapshot.loadBalancer();
        if (loadBalancer == null) {
            return;
        }

        stateLock.lock();
        try {
            final XdsLoadBalancer prevLoadBalancer = this.loadBalancer;
            if (prevLoadBalancer != null) {
                prevLoadBalancer.removeEndpointsListener(this);
            }
            this.loadBalancer = loadBalancer;
            loadBalancer.addEndpointsListener(this);
        } finally {
            stateLock.unlock();
        }
    }

    private void maybeCompleteInitialEndpointsFuture(List<Endpoint> endpoints) {
        if (endpoints != UNINITIALIZED_ENDPOINTS && !initialEndpointsFuture.isDone()) {
            initialEndpointsFuture.complete(endpoints);
        }
    }

    @Nullable
    @Override
    protected List<Endpoint> latestValue() {
        final List<Endpoint> endpoints = endpoints();
        if (endpoints == UNINITIALIZED_ENDPOINTS) {
            return null;
        } else {
            return endpoints;
        }
    }

    @Override
    public List<Endpoint> endpoints() {
        return endpoints;
    }

    @Override
    public EndpointSelectionStrategy selectionStrategy() {
        return selectionStrategy;
    }

    @Nullable
    @Override
    public Endpoint selectNow(ClientRequestContext ctx) {
        return selector.selectNow(ctx);
    }

    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx, ScheduledExecutorService executor,
                                              long timeoutMillis) {
        return select(ctx, executor);
    }

    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                              ScheduledExecutorService executor) {
        return selector.select(ctx, executor);
    }

    @Override
    public long selectionTimeoutMillis() {
        return Flags.defaultConnectTimeoutMillis();
    }

    @Override
    public CompletableFuture<List<Endpoint>> whenReady() {
        return initialEndpointsFuture;
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        listenerRoot.close();
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        closeAsync().join();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("selectionStrategy", selectionStrategy)
                          .add("allowEmptyEndpoints", allowEmptyEndpoints)
                          .add("initialized", initialEndpointsFuture.isDone())
                          .add("endpoints", endpoints())
                          .toString();
    }

    @Override
    public void accept(List<Endpoint> endpoints) {
        this.endpoints = endpoints;
        notifyListeners(endpoints);
        maybeCompleteInitialEndpointsFuture(endpoints);
    }

    private class XdsEndpointSelectionStrategy implements EndpointSelectionStrategy {

        @Override
        public EndpointSelector newSelector(EndpointGroup endpointGroup) {
            return new XdsEndpointGroupSelector(endpointGroup);
        }
    }

    private final class XdsEndpointGroupSelector extends AbstractEndpointSelector {

        XdsEndpointGroupSelector(EndpointGroup endpointGroup) {
            super(endpointGroup);
            initialize();
        }

        @Override
        @Nullable
        public Endpoint selectNow(ClientRequestContext ctx) {
            final XdsLoadBalancer loadBalancer = XdsEndpointGroup.this.loadBalancer;
            if (loadBalancer == null) {
                return null;
            }
            return loadBalancer.selectNow(ctx);
        }
    }
}
