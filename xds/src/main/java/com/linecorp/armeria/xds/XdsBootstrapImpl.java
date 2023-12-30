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
import static com.linecorp.armeria.xds.XdsType.LISTENER;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.StaticResources;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.netty.util.concurrent.EventExecutor;

final class XdsBootstrapImpl implements XdsBootstrap {
    private final EventExecutor eventLoop;

    private final Map<ConfigSource, ConfigSourceClient> clientMap = new ConcurrentHashMap<>();
    private final WatchersStorage bootstrapStorage;

    private final BootstrapApiConfigs bootstrapApiConfigs;
    private final Consumer<GrpcClientBuilder> configClientCustomizer;
    private final Node serverNode;
    private final Deque<SafeCloseable> safeCloseables = new ArrayDeque<>();

    XdsBootstrapImpl(Bootstrap bootstrap) {
        this(bootstrap, ignored -> {});
    }

    @VisibleForTesting
    XdsBootstrapImpl(Bootstrap bootstrap, Consumer<GrpcClientBuilder> configClientCustomizer) {
        eventLoop = CommonPools.workerGroup().next();
        bootstrapStorage = new WatchersStorage(this);
        this.configClientCustomizer = configClientCustomizer;
        bootstrapApiConfigs = new BootstrapApiConfigs(bootstrap);

        if (bootstrap.hasStaticResources()) {
            final StaticResources staticResources = bootstrap.getStaticResources();
            staticResources.getListenersList()
                           .forEach(listener -> bootstrapStorage.addStaticNode(
                                   LISTENER, listener.getName(), listener));
            staticResources.getClustersList()
                           .forEach(cluster -> bootstrapStorage.addStaticNode(
                                   CLUSTER, cluster.getName(), cluster));
        }
        serverNode = bootstrap.hasNode() ? bootstrap.getNode() : Node.getDefaultInstance();
    }

    void subscribe(@Nullable ConfigSource configSource, XdsType type, String resourceName,
                   ResourceWatcher<ResourceHolder<?>> watcher) {
        final ConfigSource mappedConfigSource =
                bootstrapApiConfigs.remapConfigSource(type, configSource, resourceName);
        addSubscriber0(mappedConfigSource, type, resourceName, watcher);
    }

    void removeSubscriber0(ConfigSource configSource,
                           XdsType type, String resourceName,
                           ResourceWatcher<ResourceHolder<?>> watcher) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> removeSubscriber0(configSource, type, resourceName, watcher));
            return;
        }
        final ConfigSource mappedConfigSource =
                bootstrapApiConfigs.remapConfigSource(type, configSource, resourceName);
        final ConfigSourceClient client = clientMap.get(mappedConfigSource);
        if (client.removeSubscriber(type, resourceName, watcher)) {
            client.close();
            clientMap.remove(mappedConfigSource);
        }
    }

    private void addSubscriber0(ConfigSource configSource,
                                XdsType type, String resourceName, ResourceWatcher<ResourceHolder<?>> watcher) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> addSubscriber0(configSource, type, resourceName, watcher));
            return;
        }
        final ConfigSourceClient client = clientMap.computeIfAbsent(
                configSource, ignored -> new ConfigSourceClient(
                        configSource, eventLoop, bootstrapStorage, this, serverNode, configClientCustomizer));
        client.addSubscriber(type, resourceName, this, watcher);
    }

    @Override
    public ListenerRoot listenerRoot(String resourceName) {
        return new ListenerRoot(new WatchersStorage(this), resourceName, true);
    }

    @Override
    public ClusterRoot clusterRoot(String resourceName) {
        return new ClusterRoot(new WatchersStorage(this), resourceName, true);
    }

    @Override
    public void close() {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(this::close);
            return;
        }
        // first clear listeners so that updates are not received anymore
        bootstrapStorage.clearWatchers();
        clientMap().values().forEach(ConfigSourceClient::close);
        clientMap.clear();
        while (!safeCloseables.isEmpty()) {
            safeCloseables.poll().close();
        }
    }

    @VisibleForTesting
    Map<ConfigSource, ConfigSourceClient> clientMap() {
        return clientMap;
    }

    @Override
    public EventExecutor eventLoop() {
        return eventLoop;
    }
}
