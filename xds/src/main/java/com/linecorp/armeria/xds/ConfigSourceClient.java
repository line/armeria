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
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.netty.util.concurrent.EventExecutor;

final class ConfigSourceClient implements SafeCloseable {

    private final SubscriberStorage subscriberStorage;
    private final XdsStream stream;

    ConfigSourceClient(ConfigSource configSource,
                       EventExecutor eventLoop,
                       Node node, Consumer<GrpcClientBuilder> clientCustomizer,
                       BootstrapClusters bootstrapClusters) {
        checkArgument(configSource.hasApiConfigSource(),
                      "No api config source available in %s", configSource);
        final long fetchTimeoutMillis = initialFetchTimeoutMillis(configSource);
        subscriberStorage = new SubscriberStorage(eventLoop, fetchTimeoutMillis);
        final XdsResponseHandler handler = new DefaultResponseHandler(subscriberStorage);

        final ApiConfigSource apiConfigSource = configSource.getApiConfigSource();
        final List<GrpcService> grpcServices = apiConfigSource.getGrpcServicesList();
        checkArgument(!grpcServices.isEmpty(),
                      "At least one GrpcService should be specified for '%s'", configSource);
        final boolean ads = apiConfigSource.getApiType() == ApiType.AGGREGATED_GRPC;
        final GrpcClientBuilder builder =
                GrpcClients.builder(new GrpcServicesPreprocessor(grpcServices, bootstrapClusters));
        builder.responseTimeout(java.time.Duration.ZERO);
        builder.maxResponseLength(0);
        clientCustomizer.accept(builder);
        if (ads) {
            final SotwDiscoveryStub stub = SotwDiscoveryStub.ads(builder);
            stream = new SotwXdsStream(stub, node, Backoff.ofDefault(),
                                       eventLoop, handler, subscriberStorage);
        } else {
            stream = new CompositeXdsStream(builder, node, Backoff.ofDefault(),
                                            eventLoop, handler, subscriberStorage);
        }
    }

    void updateResources(XdsType type) {
        stream.resourcesUpdated(type);
    }

    void addSubscriber(XdsType type, String resourceName,
                       ResourceWatcher<?> watcher) {
        if (subscriberStorage.register(type, resourceName, watcher)) {
            updateResources(type);
        }
    }

    boolean removeSubscriber(XdsType type, String resourceName,
                             ResourceWatcher<?> watcher) {
        if (subscriberStorage.unregister(type, resourceName, watcher)) {
            updateResources(type);
        }
        return subscriberStorage.allSubscribers().isEmpty();
    }

    @Override
    public void close() {
        stream.close();
        subscriberStorage.close();
    }

    private static long initialFetchTimeoutMillis(ConfigSource configSource) {
        if (!configSource.hasInitialFetchTimeout()) {
            return 15_000;
        }
        final Duration timeoutDuration = configSource.getInitialFetchTimeout();
        final Instant instant = Instant.ofEpochSecond(timeoutDuration.getSeconds(), timeoutDuration.getNanos());
        final long epochMilli = instant.toEpochMilli();
        checkArgument(epochMilli >= 0, "Invalid invalidFetchTimeout received: %s (expected >= 0)",
                      timeoutDuration);
        return epochMilli;
    }
}
