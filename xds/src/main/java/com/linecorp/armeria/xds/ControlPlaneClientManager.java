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
import java.util.function.Consumer;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.netty.util.concurrent.EventExecutor;

final class ControlPlaneClientManager implements SafeCloseable {
    private final Node bootstrapNode;
    private final EventExecutor eventLoop;
    private final Consumer<GrpcClientBuilder> configClientCustomizer;
    private final BootstrapClusters bootstrapClusters;
    private final ConfigSourceMapper configSourceMapper;
    private final Map<ConfigSource, ConfigSourceClient> clientMap = new HashMap<>();
    private boolean closed;

    ControlPlaneClientManager(Bootstrap bootstrap, EventExecutor eventLoop,
                              Consumer<GrpcClientBuilder> configClientCustomizer,
                              BootstrapClusters bootstrapClusters,
                              ConfigSourceMapper configSourceMapper) {
        bootstrapNode = bootstrap.getNode();
        this.eventLoop = eventLoop;
        this.configClientCustomizer = configClientCustomizer;
        this.bootstrapClusters = bootstrapClusters;
        this.configSourceMapper = configSourceMapper;
    }

    void subscribe(ResourceNode<?> node) {
        assert eventLoop.inEventLoop();
        if (closed) {
            return;
        }
        final XdsType type = node.type();
        final String name = node.name();
        final ConfigSource configSource = node.configSource();
        checkArgument(configSource != null, "Cannot subscribe to a node without a configSource");

        final ConfigSourceClient client = clientMap.computeIfAbsent(
                configSource, ignored -> new ConfigSourceClient(configSource, eventLoop, bootstrapNode,
                                                                configClientCustomizer, bootstrapClusters,
                                                                configSourceMapper));
        client.addSubscriber(type, name, node);
    }

    void unsubscribe(ResourceNode<?> node) {
        assert eventLoop.inEventLoop();
        if (closed) {
            return;
        }
        final XdsType type = node.type();
        final String resourceName = node.name();
        final ConfigSourceClient client = clientMap.get(node.configSource());
        if (client != null && client.removeSubscriber(type, resourceName, node)) {
            client.close();
            clientMap.remove(node.configSource());
        }
    }

    Map<ConfigSource, ConfigSourceClient> clientMap() {
        return clientMap;
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
}
