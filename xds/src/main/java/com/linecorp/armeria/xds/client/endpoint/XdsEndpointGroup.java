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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.xds.client.endpoint.XdsConstants.SUBSET_LOAD_BALANCING_FILTER_NAME;
import static com.linecorp.armeria.xds.client.endpoint.XdsEndpointUtil.convertEndpoints;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ProtocolStringList;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.EndpointSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetFallbackPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetSelector;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;

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

    private static final Logger logger = LoggerFactory.getLogger(XdsEndpointGroup.class);

    private final SafeCloseable safeCloseable;

    /**
     * Creates a {@link XdsEndpointGroup} which listens to the specified listener.
     */
    public static EndpointGroup of(ListenerRoot listenerRoot) {
        requireNonNull(listenerRoot, "listenerRoot");
        return new XdsEndpointGroup(listenerRoot);
    }

    /**
     * Creates a {@link XdsEndpointGroup} based on the specified {@link ClusterSnapshot}.
     * This may be useful if one would like to create an {@link EndpointGroup} based on
     * a {@link GrpcService}.
     */
    @UnstableApi
    public static EndpointGroup of(ClusterSnapshot clusterSnapshot) {
        requireNonNull(clusterSnapshot, "clusterSnapshot");
        return new XdsEndpointGroup(clusterSnapshot);
    }

    XdsEndpointGroup(ListenerRoot listenerRoot) {
        final SnapshotWatcher<ListenerSnapshot> watcher = update -> {
            final RouteSnapshot routeSnapshot = update.routeSnapshot();
            if (routeSnapshot == null) {
                return;
            }

            final List<ClusterSnapshot> clusterSnapshots = routeSnapshot.clusterSnapshots();
            if (clusterSnapshots.isEmpty()) {
                return;
            }

            if (clusterSnapshots.size() > 1) {
                // Currently, the first cluster is only used until we implement EndpointGroupSelector.
                logger.debug("The clusters from the second one are ignored. Ignored clusters: {}",
                             clusterSnapshots.subList(1, clusterSnapshots.size()));
            }

            final ClusterSnapshot clusterSnapshot = clusterSnapshots.get(0);
            final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
            if (endpointSnapshot == null) {
                logger.debug("Skipping cluster without an endpoint. {}", clusterSnapshot.xdsResource());
                return;
            }

            final Struct filterMetadata = filterMetadata(clusterSnapshot);
            if (filterMetadata.getFieldsCount() == 0) {
                // No metadata. Use the whole endpoints.
                setEndpoints(endpointSnapshot);
                return;
            }

            final Cluster cluster = clusterSnapshot.xdsResource().resource();
            final LbSubsetConfig lbSubsetConfig = cluster.getLbSubsetConfig();
            if (lbSubsetConfig == LbSubsetConfig.getDefaultInstance()) {
                // No lbSubsetConfig. Use the whole endpoints.
                setEndpoints(endpointSnapshot);
                return;
            }
            final LbSubsetFallbackPolicy fallbackPolicy = lbSubsetConfig.getFallbackPolicy();
            if (fallbackPolicy != LbSubsetFallbackPolicy.ANY_ENDPOINT) {
                logger.warn("Currently, only {} is supported.", LbSubsetFallbackPolicy.ANY_ENDPOINT);
            }

            if (!findMatchedSubsetSelector(lbSubsetConfig, filterMetadata)) {
                // No matched subset selector. Use the whole endpoints.
                setEndpoints(endpointSnapshot);
                return;
            }
            final List<Endpoint> endpoints = convertEndpoints(endpointSnapshot.xdsResource().resource(),
                                                              filterMetadata);
            if (endpoints.isEmpty()) {
                // No matched metadata. Use the whole endpoints.
                setEndpoints(endpointSnapshot);
                return;
            }
            setEndpoints(endpoints);
        };
        listenerRoot.addSnapshotWatcher(watcher);
        safeCloseable = () -> listenerRoot.removeSnapshotWatcher(watcher);
    }

    XdsEndpointGroup(ClusterSnapshot clusterSnapshot) {
        final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
        checkArgument(endpointSnapshot != null, "No endpoints are defined for cluster %s", clusterSnapshot);
        setEndpoints(endpointSnapshot);
        safeCloseable = () -> {};
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        safeCloseable.close();
        super.doCloseAsync(future);
    }

    private void setEndpoints(EndpointSnapshot endpointSnapshot) {
        setEndpoints(convertEndpoints(endpointSnapshot.xdsResource().resource()));
    }

    private static Struct filterMetadata(ClusterSnapshot clusterSnapshot) {
        final Route route = clusterSnapshot.route();
        assert route != null;
        final RouteAction action = route.getRoute();
        return action.getMetadataMatch().getFilterMetadataOrDefault(SUBSET_LOAD_BALANCING_FILTER_NAME,
                                                                    Struct.getDefaultInstance());
    }

    private static boolean findMatchedSubsetSelector(LbSubsetConfig lbSubsetConfig, Struct filterMetadata) {
        for (LbSubsetSelector subsetSelector : lbSubsetConfig.getSubsetSelectorsList()) {
            final ProtocolStringList keysList = subsetSelector.getKeysList();
            if (filterMetadata.getFieldsCount() != keysList.size()) {
                continue;
            }
            boolean found = true;
            final Map<String, Value> filterMetadataMap = filterMetadata.getFieldsMap();
            for (String key : filterMetadataMap.keySet()) {
                if (!keysList.contains(key)) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return true;
            }
        }
        return false;
    }
}
