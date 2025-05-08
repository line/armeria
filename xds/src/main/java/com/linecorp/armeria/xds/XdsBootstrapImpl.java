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

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.function.Consumer;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.xds.client.endpoint.XdsClusterManager;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.netty.util.concurrent.EventExecutor;

final class XdsBootstrapImpl implements XdsBootstrap {
    private final Bootstrap bootstrap;
    private final EventExecutor eventLoop;

    private final BootstrapListeners bootstrapListeners;
    private final ConfigSourceMapper configSourceMapper;
    private final BootstrapClusters bootstrapClusters;
    private final XdsClusterManager clusterManager;
    private final ConfigSourceManager configSourceManager;

    XdsBootstrapImpl(Bootstrap bootstrap) {
        this(bootstrap, CommonPools.workerGroup().next(), ignored -> {});
    }

    XdsBootstrapImpl(Bootstrap bootstrap, EventExecutor eventLoop) {
        this(bootstrap, eventLoop, ignored -> {});
    }

    @VisibleForTesting
    XdsBootstrapImpl(Bootstrap bootstrap, EventExecutor eventLoop,
                     Consumer<GrpcClientBuilder> configClientCustomizer) {
        this.bootstrap = bootstrap;
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        configSourceMapper = new ConfigSourceMapper(bootstrap);
        clusterManager = XdsClusterManager.of(eventLoop, bootstrap);
        bootstrapClusters = new BootstrapClusters(bootstrap, eventLoop, clusterManager);
        bootstrapListeners = new BootstrapListeners(bootstrap);
        configSourceManager = new ConfigSourceManager(bootstrap, eventLoop,
                                                      configClientCustomizer, bootstrapClusters);
    }

    @Override
    public ListenerRoot listenerRoot(String resourceName) {
        requireNonNull(resourceName, "resourceName");
        final SubscriptionContext context =
                new DefaultSubscriptionContext(eventLoop, clusterManager, bootstrapClusters,
                                               configSourceMapper, configSourceManager);
        return new ListenerRoot(context, resourceName, bootstrapListeners);
    }

    @Override
    public ClusterRoot clusterRoot(String resourceName) {
        requireNonNull(resourceName, "resourceName");
        final SubscriptionContext context =
                new DefaultSubscriptionContext(eventLoop, clusterManager, bootstrapClusters,
                                               configSourceMapper, configSourceManager);
        return new ClusterRoot(context, resourceName);
    }

    @VisibleForTesting
    Map<ConfigSource, ConfigSourceClient> clientMap() {
        return configSourceManager.clientMap();
    }

    @Override
    public EventExecutor eventLoop() {
        return eventLoop;
    }

    @Override
    public Bootstrap bootstrap() {
        return bootstrap;
    }

    @Override
    public void close() {
        configSourceManager.close();
        clusterManager.close();
    }
}
