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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.HashMap;
import java.util.Map;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.client.endpoint.UpdatableXdsLoadBalancer;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.netty.util.concurrent.EventExecutor;

final class XdsClusterManager implements SafeCloseable {

    private final Map<String, ClusterResourceNode> nodes = new HashMap<>();
    private boolean closed;

    final String localClusterName;
    @Nullable
    final UpdatableXdsLoadBalancer localLoadBalancer;

    private final EventExecutor eventLoop;
    private final Bootstrap bootstrap;

    XdsClusterManager(EventExecutor eventLoop, Bootstrap bootstrap) {
        this.eventLoop = eventLoop;
        this.bootstrap = bootstrap;
        localClusterName = bootstrap.getClusterManager().getLocalClusterName();
        if (!Strings.isNullOrEmpty(localClusterName) && bootstrap.getNode().hasLocality()) {
            localLoadBalancer = XdsLoadBalancer.of(eventLoop, bootstrap.getNode().getLocality(), null);
        } else {
            localLoadBalancer = null;
        }
    }

    void register(Cluster cluster, SubscriptionContext context, SnapshotWatcher<ClusterSnapshot> watcher) {
        checkArgument(!nodes.containsKey(cluster.getName()),
                      "Static cluster with name '%s' already registered", cluster.getName());
        final UpdatableXdsLoadBalancer loadBalancer;
        if (cluster.getName().equals(localClusterName) && localLoadBalancer != null) {
            loadBalancer = localLoadBalancer;
        } else {
            loadBalancer = XdsLoadBalancer.of(eventLoop, bootstrap.getNode().getLocality(),
                                              localLoadBalancer);
        }
        final ClusterResourceNode node =
                StaticResourceUtils.staticCluster(context, cluster.getName(), cluster, loadBalancer, "", 0);
        node.addWatcher(watcher);
        nodes.put(cluster.getName(), node);
    }

    void register(String name, SubscriptionContext context, SnapshotWatcher<ClusterSnapshot> watcher) {
        if (closed) {
            return;
        }
        final ClusterResourceNode node = nodes.computeIfAbsent(name, ignored -> {
            // on-demand cds if not already registered
            final UpdatableXdsLoadBalancer loadBalancer =
                    XdsLoadBalancer.of(eventLoop, bootstrap.getNode().getLocality(), localLoadBalancer);
            final ConfigSource configSource = context.configSourceMapper().cdsConfigSource(name);
            final ClusterResourceNode dynamicNode =
                    new ClusterResourceNode(configSource, name, context,
                                            ResourceNodeType.DYNAMIC, loadBalancer);
            context.subscribe(dynamicNode);
            return dynamicNode;
        });
        node.addWatcher(watcher);
    }

    void unregister(String name, SnapshotWatcher<ClusterSnapshot> watcher) {
        if (closed) {
            return;
        }
        final ClusterResourceNode node = nodes.get(name);
        if (node == null) {
            return;
        }
        node.removeWatcher(watcher);
        if (!node.hasWatchers()) {
            node.close();
            nodes.remove(name);
        }
    }

    @Override
    public void close() {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(this::close);
            return;
        }
        if (closed) {
            return;
        }
        closed = true;
        for (ClusterResourceNode node : nodes.values()) {
            node.close();
        }
    }
}
