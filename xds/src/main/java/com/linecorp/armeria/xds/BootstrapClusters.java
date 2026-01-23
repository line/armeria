/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;

final class BootstrapClusters implements SnapshotWatcher<ClusterSnapshot> {

    private final Map<String, CompletableFuture<ClusterSnapshot>> initialFutures = new HashMap<>();
    private final Map<String, ClusterSnapshot> snapshots = new HashMap<>();
    private final Bootstrap bootstrap;
    private final XdsClusterManager clusterManager;
    private final List<SnapshotWatcher<? super ClusterSnapshot>> watchers;

    BootstrapClusters(Bootstrap bootstrap, XdsClusterManager clusterManager,
                      SnapshotWatcher<Object> defaultSnapshotWatcher) {
        this.bootstrap = bootstrap;
        this.clusterManager = clusterManager;
        watchers = ImmutableList.of(defaultSnapshotWatcher, this);
    }

    void initializeStaticClusters(SubscriptionContext context) {
        for (Cluster cluster: bootstrap.getStaticResources().getClustersList()) {
            initialFutures.put(cluster.getName(), new CompletableFuture<>());
        }
        for (Cluster cluster: bootstrap.getStaticResources().getClustersList()) {
            clusterManager.register(cluster, context, watchers);
        }
    }

    @Override
    public void onUpdate(@Nullable ClusterSnapshot snapshot, @Nullable Throwable t) {
        if (snapshot == null) {
            return;
        }
        final String name = snapshot.xdsResource().name();
        final CompletableFuture<ClusterSnapshot> f = initialFutures.get(name);
        assert f != null;
        if (!f.isDone()) {
            f.complete(snapshot);
        }
        snapshots.put(name, snapshot);
    }

    @Nullable
    CompletableFuture<ClusterSnapshot> clusterSnapshot(String clusterName) {
        final ClusterSnapshot snapshot = snapshots.get(clusterName);
        if (snapshot != null) {
            return UnmodifiableFuture.completedFuture(snapshot);
        }
        return initialFutures.get(clusterName);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("clusterSnapshots", initialFutures)
                          .toString();
    }
}
