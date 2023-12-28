/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.xds.XdsConverterUtil.convertEndpoints;

import java.util.concurrent.CompletableFuture;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * Provides a simple {@link EndpointGroup} which listens to a xDS cluster to select endpoints.
 * Listening to EDS can be done like the following:
 * <pre>{@code
 * XdsBootstrap xdsBootstrap = XdsBootstrap.of(...);
 * EndpointGroup endpointGroup = XdsEndpointGroup.of(xdsBootstrap, "my-cluster");
 * WebClient client = WebClient.of(SessionProtocol.HTTP, endpointGroup);
 * }</pre>
 * Currently, all {@link SocketAddress}es of a {@link ClusterLoadAssignment} are aggregated
 * to a list and added to this {@link EndpointGroup}. Features such as automatic TLS detection
 * or locality based load balancing are not supported yet.
 * Note that it is important to shut down the endpoint group to clean up resources
 * for the provided {@link XdsBootstrap}.
 */
public final class XdsEndpointGroup extends DynamicEndpointGroup {

    private final SafeCloseable config;

    /**
     * Creates a {@link XdsEndpointGroup} which listens to the specified {@code resourceName}.
     */
    public static EndpointGroup of(XdsBootstrap xdsBootstrap, XdsType type, String resourceName) {
        checkArgument(type == XdsType.LISTENER || type == XdsType.CLUSTER,
                      "Received %s but only LISTENER is supported.", type);
        return new XdsEndpointGroup(xdsBootstrap, type, resourceName, true);
    }

    /**
     * Creates a {@link XdsEndpointGroup} which listens to the specified {@code resourceName}.
     *
     * @param autoSubscribe if {@code true} will query the resource from the remote control plane.
     */
    public static EndpointGroup of(XdsBootstrap xdsBootstrap, XdsType type, String resourceName,
                                   boolean autoSubscribe) {
        checkArgument(type == XdsType.LISTENER || type == XdsType.CLUSTER,
                      "Received %s but only LISTENER is supported.", type);
        return new XdsEndpointGroup(xdsBootstrap, type, resourceName, autoSubscribe);
    }

    @VisibleForTesting
    XdsEndpointGroup(XdsBootstrap xdsBootstrap, XdsType type, String resourceName, boolean autoSubscribe) {
        final EndpointNode endpointNode;
        switch (type) {
            case CLUSTER:
                final ClusterRoot clusterConfig = xdsBootstrap.clusterRoot(resourceName, autoSubscribe);
                endpointNode = clusterConfig.endpointNode();
                config = clusterConfig;
                break;
            case LISTENER:
                final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(resourceName, autoSubscribe);
                endpointNode = listenerRoot.routeNode()
                                           .clusterNode((virtualHost, route) -> true)
                                           .endpointNode();
                config = listenerRoot;
                break;
            default:
                throw new IllegalArgumentException("Unsupported config");
        }
        endpointNode.addListener(new ResourceWatcher<EndpointResourceHolder>() {
            @Override
            public void onChanged(EndpointResourceHolder update) {
                setEndpoints(convertEndpoints(update.data()));
            }
        });
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        config.close();
        super.doCloseAsync(future);
    }
}
