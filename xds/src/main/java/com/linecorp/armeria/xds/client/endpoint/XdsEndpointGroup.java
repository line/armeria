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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.Lock;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.AbstractListenable;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.ClusterManager.State;

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
 */
@UnstableApi
public final class XdsEndpointGroup extends AbstractListenable<List<Endpoint>> implements EndpointGroup {

    /**
     * Creates a {@link XdsEndpointGroup} which listens to the specified listener.
     */
    public static EndpointGroup of(ListenerRoot listenerRoot) {
        requireNonNull(listenerRoot, "listenerRoot");
        return of(listenerRoot, false);
    }

    /**
     * Creates a {@link XdsEndpointGroup} which listens to the specified listener.
     */
    public static EndpointGroup of(ListenerRoot listenerRoot, boolean allowEmptyEndpoints) {
        requireNonNull(listenerRoot, "listenerRoot");
        return new XdsEndpointGroup(new ClusterManager(listenerRoot), allowEmptyEndpoints);
    }

    /**
     * Creates a {@link XdsEndpointGroup} based on the specified {@link ClusterSnapshot}.
     * This may be useful if one would like to create an {@link EndpointGroup} based on
     * a {@link GrpcService}.
     */
    @UnstableApi
    public static EndpointGroup of(ClusterSnapshot clusterSnapshot) {
        requireNonNull(clusterSnapshot, "clusterSnapshot");
        return new XdsEndpointGroup(new ClusterManager(clusterSnapshot), false);
    }

    private static final List<Endpoint> UNINITIALIZED_ENDPOINTS = Collections.unmodifiableList(
            new ArrayList<>());

    private final XdsEndpointSelectionStrategy selectionStrategy;
    private final boolean allowEmptyEndpoints;
    private volatile State state = State.INITIAL_STATE;
    private final Lock stateLock = new ReentrantShortLock();
    private final CompletableFuture<List<Endpoint>> initialEndpointsFuture = new CompletableFuture<>();

    private final ClusterManager clusterManager;
    private final XdsEndpointSelector selector;

    XdsEndpointGroup(ClusterManager clusterManager, boolean allowEmptyEndpoints) {
        selectionStrategy = new XdsEndpointSelectionStrategy(clusterManager);
        this.allowEmptyEndpoints = allowEmptyEndpoints;
        selector = selectionStrategy.newSelector(this);
        clusterManager.addListener(this::updateState);
        this.clusterManager = clusterManager;
    }

    private void updateState(State state) {
        if (!allowEmptyEndpoints && Iterables.isEmpty(state.endpoints())) {
            return;
        }
        stateLock.lock();
        try {
            // The listenerSnapshot should be checked along with the endpoint list
            // to be sure that there is no meaningful change.
            // For instance, if header matching rules are updated, even with no
            // endpoint update the endpoint selection result could be completely different.
            if (Objects.equals(this.state, state)) {
                return;
            }
            this.state = state;
        } finally {
            stateLock.unlock();
        }

        maybeCompleteInitialEndpointsFuture(state.endpoints());
        notifyListeners(state.endpoints());
    }

    private void maybeCompleteInitialEndpointsFuture(List<Endpoint> endpoints) {
        if (endpoints != UNINITIALIZED_ENDPOINTS && !initialEndpointsFuture.isDone()) {
            initialEndpointsFuture.complete(endpoints);
        }
    }

    @Override
    protected List<Endpoint> latestValue() {
        final List<Endpoint> endpoints = state.endpoints();
        if (endpoints == UNINITIALIZED_ENDPOINTS) {
            return null;
        } else {
            return endpoints;
        }
    }

    @Override
    public List<Endpoint> endpoints() {
        return state.endpoints();
    }

    @Override
    public EndpointSelectionStrategy selectionStrategy() {
        return selectionStrategy;
    }

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
        return clusterManager.closeAsync();
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
                          .add("state", state)
                          .add("clusterManager", clusterManager)
                          .toString();
    }

    private static class XdsEndpointSelectionStrategy implements EndpointSelectionStrategy {

        private final ClusterManager clusterManager;

        XdsEndpointSelectionStrategy(ClusterManager clusterManager) {
            this.clusterManager = clusterManager;
        }

        @Override
        public XdsEndpointSelector newSelector(EndpointGroup endpointGroup) {
            return new XdsEndpointSelector(clusterManager, endpointGroup);
        }
    }
}
