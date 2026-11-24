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

package com.linecorp.armeria.xds.it;

import static io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType.AGGREGATED_DELTA_GRPC;
import static io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType.AGGREGATED_GRPC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;
import com.linecorp.armeria.xds.ClusterRoot;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.controlplane.server.exception.RequestException;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;

class VersionPersistenceOnReconnectTest {

    private static final String GROUP = "key";
    private static final String CLUSTER_NAME = "cluster1";
    private static final String BOOTSTRAP_CLUSTER_NAME = "bootstrap-cluster";

    private static final RequestTracker tracker = new RequestTracker();
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    @RegisterExtension
    @Order(0)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer ds = new V3DiscoveryServer(tracker, cache);
            sb.service(GrpcService.builder()
                                  .addService(ds.getAggregatedDiscoveryServiceImpl())
                                  .addService(ds.getClusterDiscoveryServiceImpl())
                                  .addService(ds.getEndpointDiscoveryServiceImpl())
                                  .build());
        }
    };

    @RegisterExtension
    @Order(1)
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    static Stream<Arguments> protocols() {
        return Stream.of(
                Arguments.of(AGGREGATED_GRPC),
                Arguments.of(AGGREGATED_DELTA_GRPC));
    }

    @ParameterizedTest
    @MethodSource("protocols")
    void versionPersistedOnReconnect(ApiType apiType) throws Exception {
        tracker.reset();
        cache.setSnapshot(GROUP, snapshot("1"));

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(
                bootstrapYaml(apiType).formatted(server.httpPort()), Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ClusterRoot clusterRoot = xdsBootstrap.clusterRoot(CLUSTER_NAME)) {
            final RecordingWatcher watcher = new RecordingWatcher();
            clusterRoot.addSnapshotWatcher(watcher);

            // Step 1: wait for the initial snapshot to be received
            await().untilAsserted(() -> assertThat(watcher.snapshots).isNotEmpty());

            // Step 2: verify the first request had empty version (SotW) or
            // empty initial_resource_versions (Delta)
            if (apiType == AGGREGATED_GRPC) {
                final DiscoveryRequest firstReq = tracker.firstSotw("Cluster");
                assertThat(firstReq).isNotNull();
                assertThat(firstReq.getVersionInfo()).isEmpty();
            } else {
                final DeltaDiscoveryRequest firstReq = tracker.firstDelta("Cluster");
                assertThat(firstReq).isNotNull();
                assertThat(firstReq.getInitialResourceVersionsMap()).isEmpty();
            }

            // Step 3: force-close all open gRPC streams via ctx.cancel()
            tracker.reset();
            final ServiceRequestContextCaptor captor = server.requestContextCaptor();
            await().untilAsserted(() -> assertThat(captor.isEmpty()).isFalse());
            for (ServiceRequestContext ctx : captor.all()) {
                ctx.cancel();
            }

            // Step 4: push a new snapshot so the server can respond after reconnection
            cache.setSnapshot(GROUP, snapshot("2"));

            // Step 5: verify the first request on the new stream carries the version
            // but NOT the nonce (nonces are stream-scoped and must not persist)
            if (apiType == AGGREGATED_GRPC) {
                await().untilAsserted(() -> {
                    final DiscoveryRequest reconnectReq = tracker.firstSotw("Cluster");
                    assertThat(reconnectReq).isNotNull();
                    assertThat(reconnectReq.getVersionInfo()).isEqualTo("1");
                    assertThat(reconnectReq.getResponseNonce()).isEmpty();
                });
            } else {
                await().untilAsserted(() -> {
                    final DeltaDiscoveryRequest reconnectReq = tracker.firstDelta("Cluster");
                    assertThat(reconnectReq).isNotNull();
                    assertThat(reconnectReq.getInitialResourceVersionsMap())
                            .containsKey(CLUSTER_NAME);
                    assertThat(reconnectReq.getResponseNonce()).isEmpty();
                });
            }
        }
    }

    private static Snapshot snapshot(String version) {
        final Cluster cluster = XdsResourceReader.fromYaml(
                //language=YAML
                """
                name: %s
                type: EDS
                connect_timeout: 1s
                eds_cluster_config:
                  eds_config:
                    ads: {}
                """.formatted(CLUSTER_NAME), Cluster.class);
        final ClusterLoadAssignment endpoint = XdsResourceReader.fromYaml(
                //language=YAML
                """
                cluster_name: %s
                endpoints:
                - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: 127.0.0.1
                          port_value: 8080
                """.formatted(CLUSTER_NAME), ClusterLoadAssignment.class);
        return Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(endpoint),
                               ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                               version);
    }

    private static String bootstrapYaml(ApiType apiType) {
        //language=YAML
        return """
                dynamic_resources:
                  ads_config:
                    api_type: %s
                    grpc_services:
                      - envoy_grpc:
                          cluster_name: %s
                  cds_config:
                    ads: {}
                static_resources:
                  clusters:
                    - name: %s
                      type: STATIC
                      load_assignment:
                        cluster_name: %s
                        endpoints:
                          - lb_endpoints:
                              - endpoint:
                                  address:
                                    socket_address:
                                      address: 127.0.0.1
                                      port_value: %%d
                """.formatted(apiType.name(), BOOTSTRAP_CLUSTER_NAME,
                              BOOTSTRAP_CLUSTER_NAME, BOOTSTRAP_CLUSTER_NAME);
    }

    private static final class RequestTracker implements DiscoveryServerCallbacks {

        final CopyOnWriteArrayList<DiscoveryRequest> sotwRequests = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<DeltaDiscoveryRequest> deltaRequests = new CopyOnWriteArrayList<>();

        @Override
        public void onV3StreamRequest(long streamId,
                                      DiscoveryRequest request) throws RequestException {
            sotwRequests.add(request);
        }

        @Override
        public void onV3StreamDeltaRequest(long streamId,
                                           DeltaDiscoveryRequest request) throws RequestException {
            deltaRequests.add(request);
        }

        @Nullable
        DiscoveryRequest firstSotw(String typeUrlFragment) {
            return sotwRequests.stream()
                               .filter(r -> r.getTypeUrl().contains(typeUrlFragment))
                               .findFirst()
                               .orElse(null);
        }

        @Nullable
        DeltaDiscoveryRequest firstDelta(String typeUrlFragment) {
            return deltaRequests.stream()
                                .filter(r -> r.getTypeUrl().contains(typeUrlFragment))
                                .findFirst()
                                .orElse(null);
        }

        void reset() {
            sotwRequests.clear();
            deltaRequests.clear();
        }
    }

    private static final class RecordingWatcher implements SnapshotWatcher<ClusterSnapshot> {

        final CopyOnWriteArrayList<ClusterSnapshot> snapshots = new CopyOnWriteArrayList<>();

        @Override
        public void onUpdate(ClusterSnapshot snapshot, Throwable error) {
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
    }
}
