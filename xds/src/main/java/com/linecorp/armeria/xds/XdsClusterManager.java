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
import java.util.List;
import java.util.Map;

import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.ListenerManager.WatcherKey;
import com.linecorp.armeria.xds.SnapshotStream.Subscription;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.concurrent.EventExecutor;

final class XdsClusterManager implements SafeCloseable {

    private final Map<String, ClusterStream> nodes = new HashMap<>();
    private boolean closed;

    private final EventExecutor eventLoop;
    private final LoadBalancerFactoryPool loadBalancerFactoryPool;

    private final Map<WatcherKey, Subscription> subscriptions = new HashMap<>();

    XdsClusterManager(EventExecutor eventLoop, Bootstrap bootstrap, MeterIdPrefix meterIdPrefix,
                      MeterRegistry meterRegistry) {
        this.eventLoop = eventLoop;
        final String localClusterName = bootstrap.getClusterManager().getLocalClusterName();
        loadBalancerFactoryPool = new LoadBalancerFactoryPool(localClusterName, meterIdPrefix,
                                                              meterRegistry, eventLoop, bootstrap);
    }

    void register(Cluster cluster, SubscriptionContext context,
                  List<SnapshotWatcher<? super ClusterSnapshot>> watchers) {
        checkArgument(!nodes.containsKey(cluster.getName()),
                      "Cluster with name '%s' already registered", cluster.getName());
        final ClusterStream node = new ClusterStream(new ClusterXdsResource(cluster), context,
                                                     loadBalancerFactoryPool);
        nodes.put(cluster.getName(), node);
        eventLoop.execute(() -> {
            for (SnapshotWatcher<? super ClusterSnapshot> watcher : watchers) {
                final Subscription subscription = node.subscribe(watcher);
                subscriptions.put(new WatcherKey(watcher, cluster.getName()), subscription);
            }
        });
    }

    void register(String name, SubscriptionContext context, SnapshotWatcher<ClusterSnapshot> watcher) {
        if (closed) {
            return;
        }
        final ClusterStream node = nodes.computeIfAbsent(name, ignored -> {
            // on-demand cds if not already registered
            return new ClusterStream(name, context, loadBalancerFactoryPool);
        });
        final Subscription subscription = node.subscribe(watcher);
        subscriptions.put(new WatcherKey(watcher, name), subscription);
    }

    void unregister(String name, SnapshotWatcher<ClusterSnapshot> watcher) {
        if (closed) {
            return;
        }
        final WatcherKey key = new WatcherKey(watcher, name);
        final Subscription subscription = subscriptions.remove(key);
        if (subscription != null) {
            subscription.close();
            final ClusterStream node = nodes.get(name);
            if (node != null && !node.hasWatchers()) {
                nodes.remove(name);
            }
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
        for (Subscription subscription : subscriptions.values()) {
            subscription.close();
        }
        loadBalancerFactoryPool.close();
    }
}
