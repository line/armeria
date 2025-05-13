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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.HashMap;
import java.util.Map;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.client.endpoint.XdsClusterManager;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.StaticResources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.grpc.Status;
import io.netty.util.concurrent.EventExecutor;

final class BootstrapClusters implements SnapshotWatcher<ClusterSnapshot> {

    private final Map<String, ClusterSnapshot> clusterSnapshots = new HashMap<>();
    private final Map<String, XdsLoadBalancer> loadBalancers = new HashMap<>();
    private final XdsClusterManager clusterManager;

    BootstrapClusters(Bootstrap bootstrap, EventExecutor eventLoop, XdsClusterManager clusterManager) {
        this.clusterManager = clusterManager;
        final SubscriptionContext context = new StaticSubscriptionContext(eventLoop, clusterManager);

        final String localClusterName = bootstrap.getClusterManager().getLocalClusterName();
        if (!Strings.isNullOrEmpty(localClusterName) && bootstrap.getNode().hasLocality()) {
            final Cluster bootstrapLocalCluster = localCluster(localClusterName, bootstrap);
            checkArgument(bootstrapLocalCluster != null,
                          "A static cluster must be defined for localClusterName '%s'",
                          localClusterName);
            checkArgument(!bootstrapLocalCluster.hasEdsClusterConfig(),
                          "Static cluster '%s' cannot use EDS", localClusterName);
            StaticResourceUtils.staticCluster(context, localClusterName, this, bootstrapLocalCluster);
        }

        if (bootstrap.hasStaticResources()) {
            final StaticResources staticResources = bootstrap.getStaticResources();
            for (Cluster cluster : staticResources.getClustersList()) {
                if (clusterSnapshots.containsKey(cluster.getName())) {
                    continue;
                }
                checkArgument(!cluster.hasEdsClusterConfig(),
                              "Static cluster '%s' cannot use EDS", cluster.getName());
                StaticResourceUtils.staticCluster(context, cluster.getName(), this, cluster);
            }
        }
    }

    @Nullable
    private static Cluster localCluster(String localClusterName, Bootstrap bootstrap) {
        for (Cluster cluster: bootstrap.getStaticResources().getClustersList()) {
            if (localClusterName.equals(cluster.getName())) {
                return cluster;
            }
        }
        return null;
    }

    @Override
    public void snapshotUpdated(ClusterSnapshot newSnapshot) {
        final String name = newSnapshot.xdsResource().name();
        final XdsLoadBalancer loadBalancer = clusterManager.get(name);
        if (loadBalancer != null) {
            loadBalancers.put(name, loadBalancer);
        }
        clusterSnapshots.put(name, newSnapshot);
    }

    @Nullable
    ClusterSnapshot clusterSnapshot(String clusterName) {
        return clusterSnapshots.get(clusterName);
    }

    @Nullable
    Cluster cluster(String clusterName) {
        final ClusterSnapshot clusterSnapshot = clusterSnapshots.get(clusterName);
        if (clusterSnapshot == null) {
            return null;
        }
        return clusterSnapshot.xdsResource().resource();
    }

    @Nullable
    XdsLoadBalancer loadBalancer(String clusterName) {
        return loadBalancers.get(clusterName);
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
