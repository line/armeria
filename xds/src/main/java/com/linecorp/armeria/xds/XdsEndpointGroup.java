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
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * Provides a simple {@link EndpointGroup} which listens to a xDS cluster to select endpoints.
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
public final class XdsEndpointGroup extends DynamicEndpointGroup {

    private final SafeCloseable safeCloseable;

    /**
     * Creates a {@link XdsEndpointGroup} which listens to the specified listener.
     */
    public static EndpointGroup of(ListenerRoot listenerRoot) {
        requireNonNull(listenerRoot, "listenerRoot");
        return new XdsEndpointGroup(listenerRoot);
    }

    XdsEndpointGroup(ListenerRoot listenerRoot) {
        final SnapshotWatcher<ListenerSnapshot> watcher = update -> {
            final RouteSnapshot routeSnapshot = update.routeSnapshot();
            if (routeSnapshot == null) {
                return;
            }
            final List<ClusterLoadAssignment> endpoints =
                    routeSnapshot.clusterSnapshots().stream()
                                 .map(ClusterSnapshot::endpointSnapshot)
                                 .filter(Objects::nonNull)
                                 .map(endpointSnapshot -> endpointSnapshot.holder().resource())
                                 .collect(Collectors.toList());
            setEndpoints(convertEndpoints(endpoints));
        };
        listenerRoot.addSnapshotWatcher(watcher);
        safeCloseable = () -> listenerRoot.removeSnapshotWatcher(watcher);
    }

    XdsEndpointGroup(ClusterSnapshot clusterSnapshot) {
        final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
        checkArgument(endpointSnapshot != null, "No endpoints are defined for cluster %s", clusterSnapshot);
        setEndpoints(convertEndpoints(clusterSnapshot.endpointSnapshot().holder().resource()));
        safeCloseable = () -> {};
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        safeCloseable.close();
        super.doCloseAsync(future);
    }
}
