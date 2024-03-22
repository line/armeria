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

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
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
 */
@UnstableApi
public final class XdsEndpointGroup extends DynamicEndpointGroup {

    /**
     * Creates a {@link XdsEndpointGroup} which listens to the specified listener.
     */
    public static EndpointGroup of(ListenerRoot listenerRoot) {
        requireNonNull(listenerRoot, "listenerRoot");
        return new XdsEndpointGroup(new ClusterManager(listenerRoot), false);
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
        return new XdsEndpointGroup(new ClusterManager(clusterSnapshot), true);
    }

    private final ClusterManager clusterManager;

    XdsEndpointGroup(ClusterManager clusterManager, boolean allowEmptyEndpoints) {
        super(new XdsEndpointSelectionStrategy(clusterManager), allowEmptyEndpoints);
        clusterManager.addListener(this::setEndpoints);
        this.clusterManager = clusterManager;
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        clusterManager.closeAsync().thenRun(() -> future.complete(null));
    }

    static class XdsEndpointSelectionStrategy implements EndpointSelectionStrategy {

        private final ClusterManager clusterManager;

        XdsEndpointSelectionStrategy(ClusterManager clusterManager) {
            this.clusterManager = clusterManager;
        }

        @Override
        public EndpointSelector newSelector(EndpointGroup endpointGroup) {
            return new XdsEndpointSelector(clusterManager, endpointGroup);
        }
    }
}
