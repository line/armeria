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
import static com.linecorp.armeria.xds.XdsType.ENDPOINT;
import static com.linecorp.armeria.xds.XdsType.LISTENER;
import static com.linecorp.armeria.xds.XdsType.ROUTE;
import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Message;

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
    private final WatchersStorage watchersStorage;

    private final BootstrapApiConfigs bootstrapApiConfigs;
    private final Consumer<GrpcClientBuilder> configClientCustomizer;
    private final Node node;
    private final Deque<SafeCloseable> safeCloseables = new ArrayDeque<>();

    XdsBootstrapImpl(Bootstrap bootstrap) {
        this(bootstrap, ignored -> {});
    }

    @VisibleForTesting
    XdsBootstrapImpl(Bootstrap bootstrap, Consumer<GrpcClientBuilder> configClientCustomizer) {
        eventLoop = CommonPools.workerGroup().next();
        watchersStorage = new WatchersStorage();
        this.configClientCustomizer = configClientCustomizer;
        bootstrapApiConfigs = new BootstrapApiConfigs(bootstrap);

        if (bootstrap.hasStaticResources()) {
            final StaticResources staticResources = bootstrap.getStaticResources();

            final List<SafeCloseable> staticListenerCloseables =
                    staticResources.getListenersList().stream()
                                   .map(listener -> addStaticNode(LISTENER.typeUrl(), listener.getName(),
                                                                  listener))
                                   .collect(Collectors.toList());
            safeCloseables.addAll(staticListenerCloseables);
            final List<SafeCloseable> staticClusterCloseables =
                    staticResources.getClustersList().stream()
                                   .map(cluster -> addStaticNode(CLUSTER.typeUrl(), cluster.getName(),
                                                                 cluster))
                                   .collect(Collectors.toList());
            safeCloseables.addAll(staticClusterCloseables);
        }
        node = bootstrap.hasNode() ? bootstrap.getNode() : Node.getDefaultInstance();
    }

    SafeCloseable subscribe(XdsType type, String resourceName) {
        return subscribe(null, type, resourceName);
    }

    SafeCloseable subscribe(@Nullable ConfigSource configSource, XdsType type, String resourceName) {
        final ConfigSource mappedConfigSource =
                bootstrapApiConfigs.remapConfigSource(type, configSource, resourceName);
        addSubscriber0(mappedConfigSource, type, resourceName);
        final AtomicBoolean executeOnceGuard = new AtomicBoolean();
        return () -> removeSubscriber0(mappedConfigSource, type, resourceName, executeOnceGuard);
    }

    private void removeSubscriber0(ConfigSource configSourceKey,
                                   XdsType type, String resourceName,
                                   AtomicBoolean executeOnceGuard) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> removeSubscriber0(configSourceKey, type, resourceName, executeOnceGuard));
            return;
        }
        if (!executeOnceGuard.compareAndSet(false, true)) {
            return;
        }
        final ConfigSourceClient client = clientMap.get(configSourceKey);
        if (client.removeSubscriber(type, resourceName)) {
            client.close();
            clientMap.remove(configSourceKey);
        }
    }

    private void addSubscriber0(ConfigSource configSource,
                                XdsType type, String resourceName) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> addSubscriber0(configSource, type, resourceName));
            return;
        }
        final ConfigSourceClient client = clientMap.computeIfAbsent(
                configSource, ignored -> new ConfigSourceClient(
                        configSource, eventLoop, watchersStorage, this, node, configClientCustomizer));
        client.addSubscriber(type, resourceName, this);
    }

    SafeCloseable addStaticNode(String typeUrl, String resourceName, Message t) {
        final ResourceParser resourceParser = XdsResourceParserUtil.fromTypeUrl(typeUrl);
        if (resourceParser == null) {
            throw new IllegalArgumentException("Invalid type url: " + typeUrl);
        }
        final ResourceHolder<?> parsed = resourceParser.parse(t);
        final XdsType type = resourceParser.type();
        final StaticResourceNode<?> watcher = new StaticResourceNode<>(this, parsed);
        addStaticNode0(parsed.type(), resourceName, watcher);
        final AtomicBoolean executeOnceGuard = new AtomicBoolean();
        return () -> removeStaticNode0(type, resourceName, watcher, executeOnceGuard);
    }

    private void addStaticNode0(XdsType type, String resourceName,
                                StaticResourceNode<?> watcher) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> addStaticNode0(type, resourceName, watcher));
            return;
        }
        watchersStorage.addNode(type, resourceName, watcher);
    }

    private void removeStaticNode0(XdsType type, String resourceName,
                                   StaticResourceNode<?> watcher,
                                   AtomicBoolean executeOnceGuard) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> removeStaticNode0(type, resourceName, watcher, executeOnceGuard));
            return;
        }
        if (!executeOnceGuard.compareAndSet(false, true)) {
            return;
        }
        watchersStorage.removeNode(type, resourceName, watcher);
    }

    @Override
    public ListenerRoot listenerRoot(String resourceName) {
        return listenerRoot(resourceName, true);
    }

    @Override
    public ListenerRoot listenerRoot(String resourceName, boolean autoSubscribe) {
        return new ListenerRoot(this, resourceName, autoSubscribe);
    }

    @Override
    public ClusterRoot clusterRoot(String resourceName) {
        return clusterRoot(resourceName, true);
    }

    @Override
    public ClusterRoot clusterRoot(String resourceName, boolean autoSubscribe) {
        return new ClusterRoot(this, resourceName, autoSubscribe);
    }

    SafeCloseable addListener(
            XdsType type, String resourceName, ResourceWatcher<? extends ResourceHolder<?>> watcher) {
        requireNonNull(resourceName, "resourceName");
        requireNonNull(type, "type");
        @SuppressWarnings("unchecked")
        final ResourceWatcher<ResourceHolder<?>> cast =
                (ResourceWatcher<ResourceHolder<?>>) requireNonNull(watcher, "watcher");
        eventLoop.execute(() -> watchersStorage.addWatcher(type, resourceName, cast));
        return () -> eventLoop.execute(() -> watchersStorage.removeWatcher(type, resourceName, cast));
    }

    private void removeListener(
            XdsType type, String resourceName, ResourceWatcher<? extends ResourceHolder<?>> watcher) {
        requireNonNull(resourceName, "resourceName");
        requireNonNull(type, "type");
        @SuppressWarnings("unchecked")
        final ResourceWatcher<ResourceHolder<?>> cast =
                (ResourceWatcher<ResourceHolder<?>>) requireNonNull(watcher, "watcher");
        eventLoop.execute(() -> watchersStorage.removeWatcher(type, resourceName, cast));
    }

    SafeCloseable addListenerWatcher(String resourceName,
                                     ResourceWatcher<ListenerResourceHolder> watcher) {
        return addListener(LISTENER, resourceName, watcher);
    }

    void removeListenerWatcher(String resourceName,
                               ResourceWatcher<ListenerResourceHolder> watcher) {
        removeListener(LISTENER, resourceName, watcher);
    }

    SafeCloseable addRouteWatcher(String resourceName, ResourceWatcher<RouteResourceHolder> watcher) {
        return addListener(ROUTE, resourceName, watcher);
    }

    void removeRouteWatcher(String resourceName,
                            ResourceWatcher<RouteResourceHolder> watcher) {
        removeListener(ROUTE, resourceName, watcher);
    }

    SafeCloseable addClusterWatcher(String resourceName,
                                    ResourceWatcher<ClusterResourceHolder> watcher) {
        return addListener(CLUSTER, resourceName, watcher);
    }

    void removeClusterWatcher(String resourceName,
                              ResourceWatcher<ClusterResourceHolder> watcher) {
        removeListener(CLUSTER, resourceName, watcher);
    }

    SafeCloseable addEndpointWatcher(String resourceName,
                                     ResourceWatcher<EndpointResourceHolder> watcher) {
        return addListener(ENDPOINT, resourceName, watcher);
    }

    void removeEndpointWatcher(String resourceName,
                               ResourceWatcher<EndpointResourceHolder> watcher) {
        removeListener(ENDPOINT, resourceName, watcher);
    }

    @Override
    public void close() {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(this::close);
            return;
        }
        // first clear listeners so that updates are not received anymore
        watchersStorage.clearWatchers();
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

    EventExecutor eventLoop() {
        return eventLoop;
    }
}
