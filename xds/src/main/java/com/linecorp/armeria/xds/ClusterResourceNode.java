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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.armeria.xds.XdsType.CLUSTER;
import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.grpc.Status;

final class ClusterResourceNode extends AbstractResourceNodeWithPrimer<ClusterXdsResource> {

    @Nullable
    private final VirtualHost virtualHost;
    @Nullable
    private final Route route;
    private final int index;
    private final EndpointSnapshotWatcher snapshotWatcher = new EndpointSnapshotWatcher();
    private final SnapshotWatcher<ClusterSnapshot> parentWatcher;

    ClusterResourceNode(@Nullable ConfigSource configSource,
                        String resourceName, XdsBootstrapImpl xdsBootstrap,
                        @Nullable RouteXdsResource primer,
                        SnapshotWatcher<ClusterSnapshot> parentWatcher,
                        ResourceNodeType resourceNodeType) {
        super(xdsBootstrap, configSource, CLUSTER, resourceName, primer, parentWatcher, resourceNodeType);
        this.parentWatcher = parentWatcher;
        virtualHost = null;
        route = null;
        index = -1;
    }

    ClusterResourceNode(@Nullable ConfigSource configSource,
                        String resourceName, XdsBootstrapImpl xdsBootstrap,
                        @Nullable RouteXdsResource primer, SnapshotWatcher<ClusterSnapshot> parentWatcher,
                        VirtualHost virtualHost, Route route, int index, ResourceNodeType resourceNodeType) {
        super(xdsBootstrap, configSource, CLUSTER, resourceName, primer, parentWatcher, resourceNodeType);
        this.parentWatcher = parentWatcher;
        this.virtualHost = requireNonNull(virtualHost, "virtualHost");
        this.route = requireNonNull(route, "route");
        this.index = index;
    }

    @Override
    public void doOnChanged(ClusterXdsResource resource) {
        final Cluster cluster = resource.resource();
        if (cluster.hasLoadAssignment()) {
            final ClusterLoadAssignment loadAssignment = cluster.getLoadAssignment();
            final EndpointResourceNode node =
                    StaticResourceUtils.staticEndpoint(xdsBootstrap(), loadAssignment.getClusterName(),
                                                       resource, snapshotWatcher, loadAssignment);
            children().add(node);
        } else if (cluster.hasEdsClusterConfig()) {
            final EdsClusterConfig edsClusterConfig = cluster.getEdsClusterConfig();
            final String serviceName = edsClusterConfig.getServiceName();
            final ConfigSource configSource = edsClusterConfig.getEdsConfig();
            final EndpointResourceNode node =
                    new EndpointResourceNode(configSource, !isNullOrEmpty(serviceName) ? serviceName
                                                                                       : cluster.getName(),
                                             xdsBootstrap(), resource,
                                             snapshotWatcher, ResourceNodeType.DYNAMIC);
            children().add(node);
            xdsBootstrap().subscribe(node);
        } else {
            parentWatcher.snapshotUpdated(new ClusterSnapshot(resource));
        }
    }

    private class EndpointSnapshotWatcher implements SnapshotWatcher<EndpointSnapshot> {
        @Override
        public void snapshotUpdated(EndpointSnapshot newSnapshot) {
            final ClusterXdsResource current = currentResource();
            if (current == null) {
                return;
            }
            if (!Objects.equals(newSnapshot.xdsResource().primer(), current)) {
                return;
            }
            parentWatcher.snapshotUpdated(
                    new ClusterSnapshot(current, newSnapshot, virtualHost, route, index));
        }

        @Override
        public void onError(XdsType type, Status status) {
            parentWatcher.onError(type, status);
        }

        @Override
        public void onMissing(XdsType type, String resourceName) {
            parentWatcher.onMissing(type, resourceName);
        }
    }
}
