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

import static com.google.common.base.Preconditions.checkArgument;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import com.google.protobuf.Duration;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.GrpcService.EnvoyGrpc;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.netty.util.concurrent.EventExecutor;

final class ConfigSourceClient implements SafeCloseable {

    private final XdsBootstrapImpl xdsBootstrap;
    private final Consumer<GrpcClientBuilder> clientCustomizer;
    private final EventExecutor eventLoop;
    private final XdsResponseHandler handler;
    private final SubscriberStorage subscriberStorage;
    private final ConfigSource configSource;
    private final Node node;

    @Nullable
    private XdsClientFactory xdsClientFactory;
    @Nullable
    private XdsStream stream;
    boolean closed;

    ConfigSourceClient(ConfigSource configSource,
                       EventExecutor eventLoop,
                       WatchersStorage watchersStorage, XdsBootstrapImpl xdsBootstrap,
                       Node node, Consumer<GrpcClientBuilder> clientCustomizer) {
        this.configSource = configSource;
        checkArgument(configSource.hasApiConfigSource(),
                      "No api config source available in %s", configSource);
        final ApiType apiType = configSource.getApiConfigSource().getApiType();
        this.xdsBootstrap = xdsBootstrap;
        this.clientCustomizer = clientCustomizer;
        this.eventLoop = eventLoop;
        final long fetchTimeoutMillis = initialFetchTimeoutMillis(configSource);
        subscriberStorage = new SubscriberStorage(eventLoop, watchersStorage, fetchTimeoutMillis);
        handler = new DefaultResponseHandler(subscriberStorage);
        this.node = node;
        // Initialization is rescheduled to avoid recursive updates to XdsBootstrap#clientMap.
        eventLoop.execute(() -> maybeStart(apiType == ApiType.AGGREGATED_GRPC));
    }

    void maybeStart(boolean ads) {
        if (closed) {
            return;
        }
        final ApiConfigSource apiConfigSource = configSource.getApiConfigSource();
        final List<GrpcService> grpcServices = apiConfigSource.getGrpcServicesList();
        // just use the first grpc service for now
        final GrpcService grpcService = grpcServices.get(0);
        final EnvoyGrpc envoyGrpc = grpcService.getEnvoyGrpc();
        xdsClientFactory = new XdsClientFactory(
                xdsBootstrap, envoyGrpc.getClusterName(),
                clientBuilder -> {
                    clientCustomizer.accept(clientBuilder);

                    if (stream != null) {
                        stream.close();
                    }
                    if (ads) {
                        final SotwDiscoveryStub stub = SotwDiscoveryStub.ads(clientBuilder);
                        stream = new SotwXdsStream(stub, node,
                                                   Backoff.ofDefault(),
                                                   eventLoop, handler, subscriberStorage);
                    } else {
                        stream = new CompositeXdsStream(clientBuilder, node,
                                                        Backoff.ofDefault(),
                                                        eventLoop, handler, subscriberStorage);
                    }

                    stream.start();
                });
    }

    void maybeUpdateResources(XdsType type) {
        if (stream != null) {
            stream.resourcesUpdated(type);
        }
    }

    void addSubscriber(XdsType type, String resourceName, XdsBootstrapImpl xdsBootstrap) {
        if (subscriberStorage.register(type, resourceName, xdsBootstrap)) {
            maybeUpdateResources(type);
        }
    }

    boolean removeSubscriber(XdsType type, String resourceName) {
        if (subscriberStorage.unregister(type, resourceName)) {
            maybeUpdateResources(type);
        }
        return subscriberStorage.allSubscribers().isEmpty();
    }

    @Override
    public void close() {
        closed = true;
        if (stream != null) {
            stream.close();
        }
        if (xdsClientFactory != null) {
            xdsClientFactory.close();
        }
        subscriberStorage.close();
    }

    private static long initialFetchTimeoutMillis(ConfigSource configSource) {
        if (!configSource.hasInitialFetchTimeout()) {
            return 15_000;
        }
        final Duration timeoutDuration = configSource.getInitialFetchTimeout();
        final Instant instant = Instant.ofEpochSecond(timeoutDuration.getSeconds(), timeoutDuration.getNanos());
        return instant.toEpochMilli();
    }
}
