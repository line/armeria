/*
 * Copyright 2026 LY Corporation
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

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.GrpcService.EnvoyGrpc;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.concurrent.EventExecutor;

final class GrpcConfigSourceStreamFactory implements XdsExtensionFactory {

    static final String NAME = "armeria.config_source.grpc";

    private final MeterRegistry meterRegistry;
    private final MeterIdPrefix meterIdPrefix;

    GrpcConfigSourceStreamFactory(MeterRegistry meterRegistry, MeterIdPrefix meterIdPrefix) {
        this.meterRegistry = meterRegistry;
        this.meterIdPrefix = meterIdPrefix;
    }

    @Override
    public String name() {
        return NAME;
    }

    ConfigSourceHandler create(ConfigSource configSource,
                               EventExecutor eventLoop,
                               Node node,
                               BootstrapClusters bootstrapClusters,
                               ConfigSourceMapper configSourceMapper,
                               XdsExtensionRegistry extensionRegistry,
                               InterestPublisher interestedStream,
                               SnapshotWatcher<Object> defaultWatcher) {
        final ApiConfigSource apiConfigSource;
        if (configSource.hasAds()) {
            apiConfigSource = configSourceMapper.bootstrapAdsConfig();
        } else if (configSource.hasApiConfigSource()) {
            apiConfigSource = configSource.getApiConfigSource();
        } else {
            throw new IllegalArgumentException("Unsupported config source: " + configSource);
        }

        final List<GrpcService> grpcServices = apiConfigSource.getGrpcServicesList();
        checkArgument(!grpcServices.isEmpty(),
                      "At least one GrpcService should be specified for '%s'", configSource);
        final GrpcService firstGrpcService = grpcServices.get(0);
        checkArgument(firstGrpcService.hasEnvoyGrpc(),
                      "Only envoyGrpc is supported for '%s'", configSource);
        final EnvoyGrpc envoyGrpc = firstGrpcService.getEnvoyGrpc();

        final GrpcClientBuilder builder =
                GrpcClients.builder(new GrpcServicesPreprocessor(grpcServices, bootstrapClusters));
        builder.responseTimeoutMillis(Long.MAX_VALUE);
        builder.maxResponseLength(0);

        final ApiType apiType = apiConfigSource.getApiType();
        checkArgument(apiType == ApiType.GRPC || apiType == ApiType.DELTA_GRPC ||
                      apiType == ApiType.AGGREGATED_GRPC ||
                      apiType == ApiType.AGGREGATED_DELTA_GRPC,
                      "Unsupported api_type: %s", apiType);
        final Function<String, DefaultConfigSourceLifecycleObserver> metersFunction =
                xdsType -> new DefaultConfigSourceLifecycleObserver(
                        meterRegistry, meterIdPrefix,
                        configSource.getConfigSourceSpecifierCase(),
                        envoyGrpc.getClusterName(), xdsType, apiType);

        final boolean isDelta =
                apiType == ApiType.AGGREGATED_DELTA_GRPC || apiType == ApiType.DELTA_GRPC;
        final boolean isAds = configSource.hasAds() ||
                              apiType == ApiType.AGGREGATED_GRPC ||
                              apiType == ApiType.AGGREGATED_DELTA_GRPC;

        final StateCoordinator stateCoordinator =
                new StateCoordinator(eventLoop, configSource, isDelta, extensionRegistry);
        final Backoff backoff = Backoff.ofDefault();

        final SnapshotStream<ParsedResources> xdsStream;
        if (isAds) {
            final ConfigSourceLifecycleObserver lifecycleObserver = metersFunction.apply("ads");
            if (isDelta) {
                final DeltaDiscoveryStub stub = DeltaDiscoveryStub.ads(builder);
                xdsStream = new AdsXdsStream(
                        owner -> new DeltaActualStream(stub, owner, eventLoop,
                                                       lifecycleObserver, node),
                        backoff, eventLoop, stateCoordinator, lifecycleObserver,
                        XdsType.discoverableTypes(), interestedStream);
            } else {
                final SotwDiscoveryStub stub = SotwDiscoveryStub.ads(builder);
                xdsStream = new AdsXdsStream(
                        owner -> new SotwActualStream(stub, owner, eventLoop,
                                                      lifecycleObserver, node),
                        backoff, eventLoop, stateCoordinator, lifecycleObserver,
                        XdsType.discoverableTypes(), interestedStream);
            }
        } else {
            if (isDelta) {
                xdsStream = new CompositeXdsStream(type -> {
                    final DeltaDiscoveryStub stub = DeltaDiscoveryStub.basic(type, builder);
                    final ConfigSourceLifecycleObserver lifecycleObserver =
                            metersFunction.apply(type.name().toLowerCase(Locale.ROOT));
                    return new AdsXdsStream(
                            owner -> new DeltaActualStream(stub, owner, eventLoop,
                                                           lifecycleObserver, node),
                            backoff, eventLoop, stateCoordinator, lifecycleObserver,
                            EnumSet.of(type), interestedStream);
                });
            } else {
                xdsStream = new CompositeXdsStream(type -> {
                    final SotwDiscoveryStub stub = SotwDiscoveryStub.basic(type, builder);
                    final ConfigSourceLifecycleObserver lifecycleObserver =
                            metersFunction.apply(type.name().toLowerCase(Locale.ROOT));
                    return new AdsXdsStream(
                            owner -> new SotwActualStream(stub, owner, eventLoop,
                                                          lifecycleObserver, node),
                            backoff, eventLoop, stateCoordinator, lifecycleObserver,
                            EnumSet.of(type), interestedStream);
                });
            }
        }
        return new ConfigSourceHandler(stateCoordinator, interestedStream, xdsStream, defaultWatcher);
    }
}
