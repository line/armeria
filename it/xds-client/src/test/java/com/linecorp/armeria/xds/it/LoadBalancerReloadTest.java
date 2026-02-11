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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.internal.client.endpoint.EndpointAttributeKeys;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LoadBalancerReloadTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> ldsCache = new SimpleCache<>(node -> GROUP);
    private static final SimpleCache<String> cdsCache = new SimpleCache<>(node -> GROUP);
    private static final AtomicLong version = new AtomicLong();

    @RegisterExtension
    static final ServerExtension ldsServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(ldsCache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .build());
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
        }
    };

    @RegisterExtension
    static final ServerExtension cdsServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cdsCache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
        }
    };

    //language=YAML
    private static final String bootstrapYaml =
            """
                dynamic_resources:
                  lds_config:
                    api_config_source:
                      api_type: GRPC
                      grpc_services:
                        - envoy_grpc:
                            cluster_name: lds-cluster
                  cds_config:
                    api_config_source:
                      api_type: GRPC
                      grpc_services:
                        - envoy_grpc:
                            cluster_name: cds-cluster
                static_resources:
                  clusters:
                    - name: lds-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: lds-cluster
                        endpoints:
                        - lb_endpoints:
                          - endpoint:
                              address:
                                socket_address:
                                  address: 127.0.0.1
                                  port_value: %s
                    - name: cds-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: cds-cluster
                        endpoints:
                        - lb_endpoints:
                          - endpoint:
                              address:
                                socket_address:
                                  address: 127.0.0.1
                                  port_value: %s
                """;

    //language=YAML
    private static final String listenerYaml =
            """
                name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                    stat_prefix: http
                    rds:
                      route_config_name: my-route
                      config_source:
                        api_config_source:
                          api_type: GRPC
                          grpc_services:
                            - envoy_grpc:
                                cluster_name: lds-cluster
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """;

    //language=YAML
    private static final String routeYaml =
            """
                name: my-route
                virtual_hosts:
                - name: local_service1
                  domains: [ "*" ]
                  routes:
                    - match:
                        prefix: /
                      route:
                        cluster: my-cluster
                """;

    //language=YAML
    private static final String clusterYaml =
            """
                name: my-cluster
                type: EDS
                eds_cluster_config:
                  eds_config:
                    api_config_source:
                      api_type: GRPC
                      grpc_services:
                        - envoy_grpc:
                            cluster_name: cds-cluster
                """;

    //language=YAML
    private static final String endpointYaml =
            """
              cluster_name: my-cluster
              endpoints:
              - lb_endpoints:
                - endpoint:
                    address:
                      socket_address:
                        address: 127.0.0.1
                        port_value: 8080
            """;

    @Test
    void testRouteReload() throws Exception {
        final Listener listener = XdsResourceReader.fromYaml(listenerYaml, Listener.class);
        final RouteConfiguration routeConfiguration =
                XdsResourceReader.fromYaml(routeYaml, RouteConfiguration.class);
        final Cluster cluster =
                XdsResourceReader.fromYaml(clusterYaml, Cluster.class);
        final ClusterLoadAssignment loadAssignment = XdsResourceReader.fromYaml(endpointYaml,
                                                                                ClusterLoadAssignment.class);

        version.incrementAndGet();
        ldsCache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(),
                                                    ImmutableList.of(listener),
                                                    ImmutableList.of(routeConfiguration),
                                                    ImmutableList.of(), version.toString()));
        cdsCache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(loadAssignment),
                                                    ImmutableList.of(), ImmutableList.of(),
                                                    ImmutableList.of(), version.toString()));

        final String formattedBootstrap = bootstrapYaml.formatted(ldsServer.httpPort(), cdsServer.httpPort());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(formattedBootstrap, Bootstrap.class);
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .meterRegistry(meterRegistry)
                                                     .build();
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener")) {
            final Deque<ListenerSnapshot> ref = new ArrayDeque<>();
            listenerRoot.addSnapshotWatcher(new SnapshotWatcher<>() {
                @Override
                public void onUpdate(@Nullable ListenerSnapshot snapshot, @Nullable Throwable t) {
                    if (snapshot != null) {
                        ref.add(snapshot);
                    }
                }
            });
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            await().untilAsserted(() -> assertThat(ref).isNotEmpty());
            ListenerSnapshot snapshot;
            long createdAtNanos = -1;
            while ((snapshot = ref.poll()) != null) {
                final XdsLoadBalancer loadBalancer =
                        snapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                .routeEntries().get(0).clusterSnapshot().loadBalancer();
                final Endpoint endpoint = loadBalancer.selectNow(ctx);
                assertThat(endpoint).isNotNull();
                createdAtNanos = EndpointAttributeKeys.createdAtNanos(endpoint);
            }

            version.incrementAndGet();
            ldsCache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(),
                                                        ImmutableList.of(listener),
                                                        ImmutableList.of(routeConfiguration),
                                                        ImmutableList.of(), version.toString()));

            await().untilAsserted(() -> {
                assertThat(ref).isNotEmpty();
                // wait until at least one snapshot with the new version is received
                assertThat(ref.getLast().xdsResource().version()).isEqualTo(version.toString());
            });

            while ((snapshot = ref.poll()) != null) {
                final XdsLoadBalancer loadBalancer =
                        snapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                .routeEntries().get(0).clusterSnapshot().loadBalancer();
                final Endpoint endpoint = loadBalancer.selectNow(ctx);
                assertThat(endpoint).isNotNull();
                assertThat(EndpointAttributeKeys.createdAtNanos(endpoint)).isEqualTo(createdAtNanos);
            }
        }
    }

    private static Map<String, Double> measureAll(MeterRegistry meterRegistry, Predicate<String> keyFilter) {
        return MoreMeters.measureAll(meterRegistry)
                         .entrySet().stream().filter(ele -> keyFilter.test(ele.getKey()))
                         .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
