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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.netty.util.concurrent.EventExecutor;

final class XdsBootstrapImpl implements XdsBootstrap {
    private final EventExecutor eventLoop;

    private final Map<ConfigSource, ConfigSourceClient> clientMap = new ConcurrentHashMap<>();

    private final BootstrapApiConfigs bootstrapApiConfigs;
    private final BootstrapClusters bootstrapClusters;
    private final Consumer<GrpcClientBuilder> configClientCustomizer;
    private final Node bootstrapNode;

    XdsBootstrapImpl(Bootstrap bootstrap) {
        this(bootstrap, ignored -> {});
    }

    @VisibleForTesting
    XdsBootstrapImpl(Bootstrap bootstrap, Consumer<GrpcClientBuilder> configClientCustomizer) {
        eventLoop = CommonPools.workerGroup().next();
        this.configClientCustomizer = configClientCustomizer;
        bootstrapApiConfigs = new BootstrapApiConfigs(bootstrap);
        bootstrapClusters = new BootstrapClusters(bootstrap, this);
        bootstrapNode = bootstrap.hasNode() ? bootstrap.getNode() : Node.getDefaultInstance();
    }

    void subscribe(ResourceNode<AbstractResourceHolder> node) {
        subscribe(null, node);
    }

    void subscribe(@Nullable ConfigSource configSource, ResourceNode<AbstractResourceHolder> node) {
        final XdsType type = node.type();
        final String name = node.name();
        final ConfigSource mappedConfigSource =
                bootstrapApiConfigs.remapConfigSource(type, configSource, name);
        subscribe0(mappedConfigSource, type, name, node);
    }

    private void subscribe0(ConfigSource configSource, XdsType type, String resourceName,
                            ResourceWatcher<AbstractResourceHolder> watcher) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> subscribe0(configSource, type, resourceName, watcher));
            return;
        }
        final ConfigSourceClient client = clientMap.computeIfAbsent(
                configSource, ignored -> new ConfigSourceClient(
                        configSource, eventLoop, bootstrapNode,
                        configClientCustomizer, bootstrapClusters));
        client.addSubscriber(type, resourceName, watcher);
    }

    void removeSubscriber(ResourceNode<AbstractResourceHolder> node) {
        removeSubscriber(null, node);
    }

    void removeSubscriber(@Nullable ConfigSource configSource, ResourceNode<AbstractResourceHolder> node) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> removeSubscriber(configSource, node));
            return;
        }
        final XdsType type = node.type();
        final String resourceName = node.name();
        final ConfigSource mappedConfigSource =
                bootstrapApiConfigs.remapConfigSource(type, configSource, resourceName);
        final ConfigSourceClient client = clientMap.get(mappedConfigSource);
        if (client.removeSubscriber(type, resourceName, node)) {
            client.close();
            clientMap.remove(mappedConfigSource);
        }
    }

    @Override
    public ListenerRoot listenerRoot(String resourceName) {
        return new ListenerRoot(this, resourceName);
    }

    @Override
    public ClusterRoot clusterRoot(String resourceName) {
        return new ClusterRoot(this, resourceName);
    }

    @Override
    public void close() {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(this::close);
            return;
        }
        clientMap().values().forEach(ConfigSourceClient::close);
        clientMap.clear();
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
