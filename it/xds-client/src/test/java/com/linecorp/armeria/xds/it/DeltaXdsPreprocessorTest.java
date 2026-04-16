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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

class DeltaXdsPreprocessorTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final String CLUSTER_NAME = "cluster1";
    private static final String LISTENER_NAME = "listener1";
    private static final String ROUTE_NAME = "route1";
    private static final String BOOTSTRAP_CLUSTER_NAME = "bootstrap-cluster";

    @RegisterExtension
    @Order(0)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .build());
            sb.http(0);
        }
    };

    @RegisterExtension
    @Order(1)
    static final ServerExtension helloServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
            sb.http(0);
        }
    };

    @RegisterExtension
    @Order(2)
    static final ServerExtension helloServer2 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
            sb.http(0);
        }
    };

    @RegisterExtension
    @Order(3)
    static EventLoopExtension eventLoop = new EventLoopExtension();

    @BeforeEach
    void beforeEach() {
        final Cluster cluster = clusterYaml(CLUSTER_NAME);
        final ClusterLoadAssignment assignment =
                endpointYaml(CLUSTER_NAME,
                             helloServer.httpSocketAddress().getHostString(),
                             helloServer.httpPort());
        final Listener listener = listenerYaml(LISTENER_NAME, ROUTE_NAME);
        final RouteConfiguration route = routeYaml(ROUTE_NAME, CLUSTER_NAME);
        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(cluster),
                        ImmutableList.of(assignment),
                        ImmutableList.of(listener),
                        ImmutableList.of(route),
                        ImmutableList.of(),
                        "1"));
    }

    @Test
    void testWithListener() {
        final Bootstrap bootstrap =
                bootstrapYaml(BOOTSTRAP_CLUSTER_NAME,
                              server.httpSocketAddress().getHostString(),
                              server.httpPort());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             XdsHttpPreprocessor xdsPreprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingWebClient blockingClient = WebClient.of(xdsPreprocessor).blocking();
            assertThat(blockingClient.get("/hello").contentUtf8()).isEqualTo("world");
        }
    }

    @Test
    void resourceUpdate() {
        final Bootstrap bootstrap =
                bootstrapYaml(BOOTSTRAP_CLUSTER_NAME,
                              server.httpSocketAddress().getHostString(),
                              server.httpPort());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             XdsHttpPreprocessor xdsPreprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingQueue<Integer> ports = new LinkedBlockingQueue<>();
            final BlockingWebClient blockingClient =
                    WebClient.builder(xdsPreprocessor)
                             .decorator((delegate, ctx, req) -> {
                                 if (ctx.endpoint() != null) {
                                     ports.add(ctx.endpoint().port());
                                 }
                                 return delegate.execute(ctx, req);
                             })
                             .build()
                             .blocking();

            // Confirm initial endpoint works
            assertThat(blockingClient.get("/hello").contentUtf8()).isEqualTo("world");

            // Update snapshot to point to helloServer2
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(clusterYaml(CLUSTER_NAME)),
                            ImmutableList.of(endpointYaml(CLUSTER_NAME,
                                                          helloServer2.httpSocketAddress().getHostString(),
                                                          helloServer2.httpPort())),
                            ImmutableList.of(listenerYaml(LISTENER_NAME, ROUTE_NAME)),
                            ImmutableList.of(routeYaml(ROUTE_NAME, CLUSTER_NAME)),
                            ImmutableList.of(),
                            "2"));

            // Wait until requests reach helloServer2
            final int newPort = helloServer2.httpPort();
            await().untilAsserted(() -> {
                assertThat(blockingClient.get("/hello").contentUtf8()).isEqualTo("world");
                assertThat(ports).contains(newPort);
            });
        }
    }

    private static Cluster clusterYaml(String name) {
        //language=YAML
        final String yaml = """
                name: %s
                type: EDS
                connect_timeout: 1s
                eds_cluster_config:
                  eds_config:
                    ads: {}
                """.formatted(name);
        return XdsResourceReader.fromYaml(yaml, Cluster.class);
    }

    private static ClusterLoadAssignment endpointYaml(String clusterName, String address, int port) {
        //language=YAML
        final String yaml =
                """
                cluster_name: %s
                endpoints:
                - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: %s
                          port_value: %s
                """.formatted(clusterName, address, port);
        return XdsResourceReader.fromYaml(yaml, ClusterLoadAssignment.class);
    }

    private static Listener listenerYaml(String name, String routeName) {
        //language=YAML
        final String yaml =
                """
                name: %s
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                    stat_prefix: http
                    rds:
                      route_config_name: %s
                      config_source:
                        ads: {}
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """.formatted(name, routeName);
        return XdsResourceReader.fromYaml(yaml, Listener.class);
    }

    private static RouteConfiguration routeYaml(String name, String clusterName) {
        //language=YAML
        final String yaml =
                """
                name: %s
                virtual_hosts:
                - name: local_service1
                  domains: [ "*" ]
                  routes:
                  - match:
                      prefix: /
                    route:
                      cluster: %s
                """.formatted(name, clusterName);
        return XdsResourceReader.fromYaml(yaml, RouteConfiguration.class);
    }

    private static Bootstrap bootstrapYaml(String clusterName, String address, int port) {
        //language=YAML
        final String yaml = """
                dynamic_resources:
                  ads_config:
                    api_type: AGGREGATED_DELTA_GRPC
                    grpc_services:
                    - envoy_grpc:
                        cluster_name: %s
                  cds_config:
                    ads: {}
                  lds_config:
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
                                address: %s
                                port_value: %s
                """.formatted(clusterName, clusterName, clusterName, address, port);
        return XdsResourceReader.fromYaml(yaml, Bootstrap.class);
    }
}
