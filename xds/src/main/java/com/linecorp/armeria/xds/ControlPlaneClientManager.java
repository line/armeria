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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.netty.util.concurrent.EventExecutor;

final class ControlPlaneClientManager implements SafeCloseable {
    private final Node bootstrapNode;
    private final EventExecutor eventLoop;
    private final BootstrapClusters bootstrapClusters;
    private final ConfigSourceMapper configSourceMapper;
    private final XdsExtensionRegistry extensionRegistry;
    private final Map<ConfigSource, ConfigSourceHandler> clientMap = new HashMap<>();
    private boolean closed;

    ControlPlaneClientManager(Bootstrap bootstrap, EventExecutor eventLoop,
                              BootstrapClusters bootstrapClusters,
                              ConfigSourceMapper configSourceMapper,
                              XdsExtensionRegistry extensionRegistry) {
        bootstrapNode = bootstrap.getNode();
        this.eventLoop = eventLoop;
        this.bootstrapClusters = bootstrapClusters;
        this.configSourceMapper = configSourceMapper;
        this.extensionRegistry = extensionRegistry;
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

        final ConfigSourceHandler client = clientMap.computeIfAbsent(configSource, this::createClient);
        client.addSubscriber(type, name, node);
    }

    void unsubscribe(ResourceNode<?> node) {
        assert eventLoop.inEventLoop();
        if (closed) {
            return;
        }
        final XdsType type = node.type();
        final String resourceName = node.name();
        final ConfigSourceHandler client = clientMap.get(node.configSource());
        if (client != null && client.removeSubscriber(type, resourceName, node)) {
            client.close();
            clientMap.remove(node.configSource());
        }
    }

    private ConfigSourceHandler createClient(ConfigSource configSource) {
        switch (configSource.getConfigSourceSpecifierCase()) {
            case PATH_CONFIG_SOURCE:
            case CUSTOM_CONFIG_SOURCE:
                final SotwConfigSourceSubscriptionFactory streamFactory = resolveStreamFactory(configSource);
                checkArgument(streamFactory != null,
                              "No SotwConfigSourceSubscriptionFactory found for: %s", configSource);
                final StateCoordinator stateCoordinator =
                        new StateCoordinator(eventLoop, configSource, false, extensionRegistry);
                final ConfigSourceSubscription stream =
                        streamFactory.create(configSource, stateCoordinator, eventLoop);
                return new ConfigSourceHandler(stateCoordinator, stream);
            case ADS:
            case API_CONFIG_SOURCE:
                final GrpcConfigSourceStreamFactory grpcFactory =
                        extensionRegistry.queryByName(GrpcConfigSourceStreamFactory.NAME,
                                                      GrpcConfigSourceStreamFactory.class);
                checkArgument(grpcFactory != null, "No GrpcConfigSourceStreamFactory registered");
                return grpcFactory.create(
                        configSource, eventLoop, bootstrapNode, bootstrapClusters,
                        configSourceMapper, extensionRegistry);
            default:
                throw new IllegalArgumentException("Unsupported config source: " + configSource);
        }
    }

    @Nullable
    private SotwConfigSourceSubscriptionFactory resolveStreamFactory(ConfigSource configSource) {
        switch (configSource.getConfigSourceSpecifierCase()) {
            case PATH_CONFIG_SOURCE:
                return extensionRegistry.queryByName(
                        PathSotwConfigSourceSubscriptionFactory.NAME,
                        SotwConfigSourceSubscriptionFactory.class);
            case CUSTOM_CONFIG_SOURCE:
                return extensionRegistry.queryByTypeUrl(
                        configSource.getCustomConfigSource().getTypeUrl(),
                        SotwConfigSourceSubscriptionFactory.class);
            default:
                return null;
        }
    }

    Map<ConfigSource, ConfigSourceHandler> clientMap() {
        return clientMap;
    }

    @Override
    public void close() {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(this::close);
            return;
        }
        closed = true;
        clientMap.values().forEach(ConfigSourceHandler::close);
        clientMap.clear();
    }
}
