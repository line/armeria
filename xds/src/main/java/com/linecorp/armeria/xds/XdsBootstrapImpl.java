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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.common.CommonPools;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.netty.util.concurrent.EventExecutor;

final class XdsBootstrapImpl implements XdsBootstrap {
    private final EventExecutor eventLoop;

    private final Map<ConfigSource, ConfigSourceClient> clientMap = new HashMap<>();

    private final BootstrapApiConfigs bootstrapApiConfigs;
    private final BootstrapListeners bootstrapListeners;
    private final BootstrapClusters bootstrapClusters;
    private final Consumer<GrpcClientBuilder> configClientCustomizer;
    private final Node bootstrapNode;
    private boolean closed;

    XdsBootstrapImpl(Bootstrap bootstrap) {
        this(bootstrap, CommonPools.workerGroup().next(), ignored -> {});
    }

    XdsBootstrapImpl(Bootstrap bootstrap, EventExecutor eventLoop) {
        this(bootstrap, eventLoop, ignored -> {});
    }

    @VisibleForTesting
    XdsBootstrapImpl(Bootstrap bootstrap, EventExecutor eventLoop,
                     Consumer<GrpcClientBuilder> configClientCustomizer) {
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        this.configClientCustomizer = configClientCustomizer;
        bootstrapApiConfigs = new BootstrapApiConfigs(bootstrap);
        bootstrapListeners = new BootstrapListeners(bootstrap);
        bootstrapClusters = new BootstrapClusters(bootstrap, this);
        bootstrapNode = bootstrap.hasNode() ? bootstrap.getNode() : Node.getDefaultInstance();
    }

    BootstrapClusters bootstrapClusters() {
        return bootstrapClusters;
    }

    void subscribe(ResourceNode<?> node) {
        final XdsType type = node.type();
        final String name = node.name();
        final ConfigSource mappedConfigSource =
                bootstrapApiConfigs.configSource(type, name, node);
        subscribe0(mappedConfigSource, type, name, node);
    }

    private void subscribe0(ConfigSource configSource, XdsType type, String resourceName,
                            ResourceWatcher<?> node) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> subscribe0(configSource, type, resourceName, node));
            return;
        }
        checkState(!closed, "Attempting to subscribe to a closed XdsBootstrap");
        final ConfigSourceClient client = clientMap.computeIfAbsent(
                configSource, ignored -> new ConfigSourceClient(
                        configSource, eventLoop, bootstrapNode,
                        configClientCustomizer, bootstrapClusters));
        client.addSubscriber(type, resourceName, node);
    }

    void unsubscribe(ResourceNode<?> node) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> unsubscribe(node));
            return;
        }
        checkState(!closed, "Attempting to unsubscribe to a closed XdsBootstrap");
        final XdsType type = node.type();
        final String resourceName = node.name();
        final ConfigSource mappedConfigSource =
                bootstrapApiConfigs.configSource(type, resourceName, node);
        final ConfigSourceClient client = clientMap.get(mappedConfigSource);
        if (client != null && client.removeSubscriber(type, resourceName, node)) {
            client.close();
            clientMap.remove(mappedConfigSource);
        }
    }

    @Override
    public ListenerRoot listenerRoot(String resourceName) {
        requireNonNull(resourceName, "resourceName");
        return new ListenerRoot(this, resourceName, bootstrapListeners);
    }

    @Override
    public ClusterRoot clusterRoot(String resourceName) {
        requireNonNull(resourceName, "resourceName");
        return new ClusterRoot(this, resourceName);
    }

    @Override
    public void close() {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(this::close);
            return;
        }
        closed = true;
        clientMap.values().forEach(ConfigSourceClient::close);
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
