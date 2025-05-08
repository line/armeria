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

package com.linecorp.armeria.xds;

import static com.linecorp.armeria.xds.StaticResourceUtils.staticCluster;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.Route.ActionCase;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.grpc.Status;

final class VirtualHostResourceNode extends AbstractResourceNodeWithPrimer<VirtualHostXdsResource> {

    private final Set<Integer> pending = new HashSet<>();
    private final List<ClusterSnapshot> clusterSnapshots = new ArrayList<>();
    private final ClusterSnapshotWatcher snapshotWatcher = new ClusterSnapshotWatcher();
    private final SnapshotWatcher<VirtualHostSnapshot> parentWatcher;
    private final int index;

    VirtualHostResourceNode(@Nullable ConfigSource configSource, String resourceName,
                            SubscriptionContext context, @Nullable RouteXdsResource primer,
                            SnapshotWatcher<VirtualHostSnapshot> parentWatcher, int index,
                            ResourceNodeType resourceNodeType) {
        super(context, configSource, XdsType.VIRTUAL_HOST, resourceName, primer, parentWatcher,
              resourceNodeType);
        this.parentWatcher = parentWatcher;
        this.index = index;
    }

    @Override
    void doOnChanged(VirtualHostXdsResource resource) {
        pending.clear();
        clusterSnapshots.clear();
        for (Route route: resource.resource().getRoutesList()) {
            final RouteAction routeAction = route.getRoute();
            final String clusterName = routeAction.getCluster();

            // add a dummy element to the index list so that we can call List.set later
            // without incurring an IndexOutOfBoundException when a snapshot is updated
            clusterSnapshots.add(null);

            if (route.getActionCase() != ActionCase.ROUTE) {
                continue;
            }

            final int index = clusterSnapshots.size() - 1;
            pending.add(index);

            final Cluster cluster = context().bootstrapClusters().cluster(clusterName);
            final ClusterResourceNode node;
            if (cluster != null) {
                node = staticCluster(context(), clusterName, resource, snapshotWatcher,
                                     index, cluster);
                children().add(node);
            } else {
                final ConfigSource configSource =
                        configSourceMapper().cdsConfigSource(clusterName);
                node = new ClusterResourceNode(configSource, clusterName, context(),
                                               resource, snapshotWatcher, index, ResourceNodeType.DYNAMIC);
                children().add(node);
                context().subscribe(node);
            }
        }
    }

    private class ClusterSnapshotWatcher implements SnapshotWatcher<ClusterSnapshot> {

        @Override
        public void snapshotUpdated(ClusterSnapshot newSnapshot) {
            final VirtualHostXdsResource current = currentResource();
            if (current == null) {
                return;
            }
            if (!Objects.equals(current, newSnapshot.xdsResource().primer())) {
                return;
            }
            clusterSnapshots.set(newSnapshot.index(), newSnapshot);
            pending.remove(newSnapshot.index());
            // checks if all clusters for the route have reported a snapshot
            if (!pending.isEmpty()) {
                return;
            }
            parentWatcher.snapshotUpdated(
                    new VirtualHostSnapshot(current, ImmutableList.copyOf(clusterSnapshots), index));
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
