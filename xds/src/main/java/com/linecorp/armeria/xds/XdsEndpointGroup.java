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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.Nullable;
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

    private final SafeCloseable closeable;
    @Nullable
    private final SafeCloseable subscribeCloseable;

    /**
     * Creates a {@link XdsEndpointGroup} which listens to the specified {@code resourceName}.
     */
    public static EndpointGroup of(XdsBootstrap xdsBootstrap, XdsType type, String resourceName) {
        checkArgument(type == XdsType.ENDPOINT, "Received %s but only ENDPOINT is supported.", type);
        return of(xdsBootstrap, type, resourceName, true);
    }

    /**
     * Creates a {@link XdsEndpointGroup} which listens to the specified {@code resourceName}.
     * @param autoSubscribe whether to subscribe to the {@link XdsBootstrap} by default.
     */
    public static EndpointGroup of(XdsBootstrap xdsBootstrap, XdsType type,
                                   String resourceName, boolean autoSubscribe) {
        return new XdsEndpointGroup(xdsBootstrap, type, resourceName, autoSubscribe);
    }

    @VisibleForTesting
    XdsEndpointGroup(XdsBootstrap xdsBootstrap, XdsType type, String resourceName, boolean autoSubscribe) {
        if (autoSubscribe) {
            subscribeCloseable = xdsBootstrap.subscribe(type, resourceName);
        } else {
            subscribeCloseable = null;
        }
        final AggregatingWatcherListener listener = new AggregatingWatcherListener() {
            @Override
            public void onEndpointUpdate(
                    Map<String, ClusterLoadAssignment> update) {
                final Set<Endpoint> endpoints = new HashSet<>();
                update.values().forEach(clusterLoadAssignment -> {
                    endpoints.addAll(convertEndpoints(clusterLoadAssignment));
                });
                setEndpoints(endpoints);
            }
        };
        closeable = new AggregatingWatcher(xdsBootstrap, type, resourceName, listener);
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        closeable.close();
        if (subscribeCloseable != null) {
            subscribeCloseable.close();
        }
        super.doCloseAsync(future);
    }
}
