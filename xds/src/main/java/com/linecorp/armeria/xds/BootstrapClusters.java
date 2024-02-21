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

package com.linecorp.armeria.xds;

import java.util.HashMap;
import java.util.Map;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.StaticResources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.grpc.Status;

final class BootstrapClusters implements SnapshotWatcher<ClusterSnapshot> {

    private final Map<String, ClusterSnapshot> clusterSnapshots = new HashMap<>();
    private final Map<String, Cluster> clusters = new HashMap<>();

    BootstrapClusters(Bootstrap bootstrap, XdsBootstrapImpl xdsBootstrap) {
        if (bootstrap.hasStaticResources()) {
            final StaticResources staticResources = bootstrap.getStaticResources();
            for (Cluster cluster : staticResources.getClustersList()) {
                if (cluster.hasLoadAssignment()) {
                    // no need to clean this cluster up since it is fully static
                    StaticResourceUtils.staticCluster(xdsBootstrap, cluster.getName(), this, cluster);
                }
                clusters.put(cluster.getName(), cluster);
            }
        }
    }

    @Override
    public void snapshotUpdated(ClusterSnapshot newSnapshot) {
        clusterSnapshots.put(newSnapshot.xdsResource().name(), newSnapshot);
    }

    @Nullable
    ClusterSnapshot clusterSnapshot(String clusterName) {
        return clusterSnapshots.get(clusterName);
    }

    @Nullable
    Cluster cluster(String clusterName) {
        return clusters.get(clusterName);
    }

    @Override
    public void onMissing(XdsType type, String resourceName) {
        throw new IllegalArgumentException("Bootstrap cluster not found for type: '" +
                                           type + "', resourceName: '" + resourceName + '\'');
    }

    @Override
    public void onError(XdsType type, Status status) {
        throw new IllegalArgumentException("Unexpected error for bootstrap cluster with type: '" +
                                           type + '\'', status.asException());
    }
}
