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

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.concurrent.EventExecutor;

final class BootstrapClusters implements SnapshotWatcher<ClusterSnapshot> {

    private final Map<String, ClusterSnapshot> clusterSnapshots = new HashMap<>();
    private final Bootstrap bootstrap;
    private final EventExecutor eventLoop;
    private final XdsClusterManager clusterManager;
    private final List<SnapshotWatcher<? super ClusterSnapshot>> watchers;

    BootstrapClusters(Bootstrap bootstrap, EventExecutor eventLoop, XdsClusterManager clusterManager,
                      MeterRegistry meterRegistry, MeterIdPrefix meterIdPrefix,
                      SnapshotWatcher<Object> defaultSnapshotWatcher) {
        this.bootstrap = bootstrap;
        this.eventLoop = eventLoop;
        this.clusterManager = clusterManager;
        watchers = ImmutableList.of(defaultSnapshotWatcher, this);
        initializePrimary(bootstrap, meterRegistry, meterIdPrefix);
    }

    private void initializePrimary(Bootstrap bootstrap, MeterRegistry meterRegistry,
                                   MeterIdPrefix meterIdPrefix) {
        final StaticSubscriptionContext context =
                new StaticSubscriptionContext(eventLoop, meterRegistry, meterIdPrefix);
        for (Cluster cluster: bootstrap.getStaticResources().getClustersList()) {
            if (!cluster.hasLoadAssignment()) {
                continue;
            }
            clusterManager.register(cluster, context, watchers, "", 0);
        }
    }

    void initializeSecondary(SubscriptionContext context) {
        for (Cluster cluster: bootstrap.getStaticResources().getClustersList()) {
            if (!cluster.hasEdsClusterConfig()) {
                continue;
            }
            clusterManager.register(cluster, context, watchers, "", 0);
        }
    }

    @Override
    public void snapshotUpdated(ClusterSnapshot newSnapshot) {
        final String name = newSnapshot.xdsResource().name();
        clusterSnapshots.put(name, newSnapshot);
    }

    @Nullable
    ClusterSnapshot clusterSnapshot(String clusterName) {
        return clusterSnapshots.get(clusterName);
    }

    @Nullable
    XdsLoadBalancer loadBalancer(String clusterName) {
        final ClusterSnapshot snapshot = clusterSnapshots.get(clusterName);
        if (snapshot == null) {
            return null;
        }
        return snapshot.loadBalancer();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("clusterSnapshots", clusterSnapshots)
                          .toString();
    }
}
