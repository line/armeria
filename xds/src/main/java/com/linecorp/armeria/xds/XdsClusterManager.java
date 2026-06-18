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
import static com.linecorp.armeria.xds.AbstractRoot.safeRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.netty.util.concurrent.EventExecutor;

final class XdsClusterManager implements SafeCloseable {

    private final Map<String, ClusterStream> nodes = new HashMap<>();
    private boolean closed;

    private final EventExecutor eventLoop;
    private final String localClusterName;

    private final List<Subscription> subscriptions = new ArrayList<>();

    XdsClusterManager(EventExecutor eventLoop, Bootstrap bootstrap) {
        this.eventLoop = eventLoop;
        localClusterName = bootstrap.getClusterManager().getLocalClusterName();
    }

    boolean hasLocalCluster() {
        return !localClusterName.isEmpty();
    }

    String localClusterName() {
        return localClusterName;
    }

    Subscription registerLocalWatcher(SnapshotWatcher<Optional<ClusterSnapshot>> watcher) {
        final ClusterStream clusterStream = nodes.get(localClusterName);
        if (clusterStream == null) {
            return SnapshotStream.<ClusterSnapshot>empty().subscribe(watcher);
        }
        return clusterStream.map(Optional::of).subscribe(watcher);
    }

    void register(Cluster cluster, SubscriptionContext context,
                  List<SnapshotWatcher<? super ClusterSnapshot>> watchers) {
        checkArgument(!nodes.containsKey(cluster.getName()),
                      "Cluster with name '%s' already registered", cluster.getName());
        final ClusterXdsResource resource =
                ClusterResourceParser.INSTANCE.parse(cluster, context.extensionRegistry(), "");
        final ClusterStream node = new ClusterStream(resource, context);
        nodes.put(cluster.getName(), node);
        for (SnapshotWatcher<? super ClusterSnapshot> watcher : watchers) {
            eventLoop.execute(safeRunnable(() -> {
                final Subscription subscription = node.subscribe(watcher);
                subscriptions.add(subscription);
            }, t -> watcher.onUpdate(null,
                                     XdsResourceException.maybeWrap(XdsType.CLUSTER,
                                                                    cluster.getName(), t))));
        }
    }

    Subscription register(String name, SubscriptionContext context,
                                SnapshotWatcher<? super ClusterSnapshot> watcher) {
        if (closed) {
            return Subscription.noop();
        }
        final ClusterStream node = nodes.computeIfAbsent(name, ignored -> {
            // on-demand cds if not already registered
            return new ClusterStream(name, context);
        });
        final Subscription subscription = node.subscribe(watcher);
        return () -> {
            subscription.close();
            if (!node.hasWatchers()) {
                nodes.remove(name);
            }
        };
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
        for (Subscription subscription : subscriptions) {
            subscription.close();
        }
    }
}
