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

import static com.linecorp.armeria.xds.XdsType.CLUSTER;
import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.grpc.Status;

final class ClusterResourceNode extends AbstractResourceNode<ClusterSnapshot> {

    @Nullable
    private final VirtualHost virtualHost;
    @Nullable
    private final Route route;
    private final int index;
    private final EndpointSnapshotWatcher snapshotWatcher = new EndpointSnapshotWatcher();

    ClusterResourceNode(@Nullable ConfigSource configSource,
                        String resourceName, XdsBootstrapImpl xdsBootstrap,
                        @Nullable ResourceHolder primer, SnapshotWatcher<? super ClusterSnapshot> parentWatcher,
                        ResourceNodeType resourceNodeType) {
        super(xdsBootstrap, configSource, CLUSTER, resourceName, primer, parentWatcher, resourceNodeType);
        virtualHost = null;
        route = null;
        index = -1;
    }

    ClusterResourceNode(@Nullable ConfigSource configSource,
                        String resourceName, XdsBootstrapImpl xdsBootstrap,
                        @Nullable ResourceHolder primer, SnapshotWatcher<ClusterSnapshot> parentWatcher,
                        VirtualHost virtualHost, Route route, int index, ResourceNodeType resourceNodeType) {
        super(xdsBootstrap, configSource, CLUSTER, resourceName, primer, parentWatcher, resourceNodeType);
        this.virtualHost = requireNonNull(virtualHost, "virtualHost");
        this.route = requireNonNull(route, "route");
        this.index = index;
    }

    @Override
    public void process(ResourceHolder update) {
        final ClusterResourceHolder holder = (ClusterResourceHolder) update;
        final Cluster cluster = holder.resource();
        if (cluster.hasLoadAssignment()) {
            final ClusterLoadAssignment loadAssignment = cluster.getLoadAssignment();
            final EndpointResourceNode node =
                    StaticResourceUtils.staticEndpoint(xdsBootstrap(), cluster.getName(),
                                                       holder, snapshotWatcher, loadAssignment);
            children().add(node);
        } else if (cluster.hasEdsClusterConfig()) {
            final ConfigSource configSource = cluster.getEdsClusterConfig().getEdsConfig();
            final EndpointResourceNode node =
                    new EndpointResourceNode(configSource, cluster.getName(), xdsBootstrap(), holder,
                                             snapshotWatcher, ResourceNodeType.DYNAMIC);
            children().add(node);
            xdsBootstrap().subscribe(configSource, node);
        }
        if (children().isEmpty()) {
            parentWatcher().snapshotUpdated(new ClusterSnapshot(holder));
        }
    }

    @Override
    public ClusterResourceHolder current() {
        return (ClusterResourceHolder) super.current();
    }

    private class EndpointSnapshotWatcher implements SnapshotWatcher<EndpointSnapshot> {
        @Override
        public void snapshotUpdated(EndpointSnapshot newSnapshot) {
            final ClusterResourceHolder current = current();
            if (current == null) {
                return;
            }
            if (!Objects.equals(newSnapshot.holder().primer(), current)) {
                return;
            }
            parentWatcher().snapshotUpdated(
                    new ClusterSnapshot(current, newSnapshot, virtualHost, route, index));
        }

        @Override
        public void onError(XdsType type, Status status) {
            parentWatcher().onError(type, status);
        }

        @Override
        public void onMissing(XdsType type, String resourceName) {
            parentWatcher().onMissing(type, resourceName);
        }
    }
}
