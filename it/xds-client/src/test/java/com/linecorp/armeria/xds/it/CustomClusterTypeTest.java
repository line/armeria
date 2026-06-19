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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

class CustomClusterTypeTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static volatile String endpointListResponse = "";

    @RegisterExtension
    static final ServerExtension controlPlane = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
        }
    };

    @RegisterExtension
    static final ServerExtension endpointServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/endpoints", (ctx, req) ->
                    HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                    endpointListResponse));
        }
    };

    @RegisterExtension
    static final ServerExtension backendServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
        }
    };

    @Test
    void customClusterTypeFetchesEndpointsFromServer() {
        endpointListResponse = endpointYaml("127.0.0.1", backendServer.httpPort());

        final Cluster cluster = clusterYaml(endpointServer.httpPort());
        final Listener listener = listenerYaml();
        final RouteConfiguration route = routeYaml();
        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(cluster), ImmutableList.of(),
                        ImmutableList.of(listener), ImmutableList.of(route),
                        ImmutableList.of(), "1"));

        final Bootstrap bootstrap = bootstrapYaml();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            assertThat(client.get("/hello").contentUtf8()).isEqualTo("world");
        }
    }

    @Test
    void customClusterTypeUpdatesOnClusterReload() {
        endpointListResponse = endpointYaml("127.0.0.1", backendServer.httpPort());

        final Cluster cluster = clusterYaml(endpointServer.httpPort());
        final Listener listener = listenerYaml();
        final RouteConfiguration route = routeYaml();
        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(cluster), ImmutableList.of(),
                        ImmutableList.of(listener), ImmutableList.of(route),
                        ImmutableList.of(), "1"));

        final Bootstrap bootstrap = bootstrapYaml();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            assertThat(client.get("/hello").contentUtf8()).isEqualTo("world");

            // Update the endpoint server response to return a different port.
            endpointListResponse = endpointYaml("127.0.0.1", controlPlane.httpPort());

            // Push a new cluster version to trigger the factory again.
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(cluster), ImmutableList.of(),
                            ImmutableList.of(listener), ImmutableList.of(route),
                            ImmutableList.of(), "2"));

            // controlPlane also serves HTTP, so we can verify the endpoint switched.
            await().untilAsserted(() ->
                    assertThat(client.get("/hello").status()).isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    private static String endpointYaml(String address, int port) {
        //language=YAML
        return """
               endpoints:
               - lb_endpoints:
                 - endpoint:
                     address:
                       socket_address:
                         address: %s
                         port_value: %d
               """.formatted(address, port);
    }

    private static Cluster clusterYaml(int endpointServerPort) {
        //language=YAML
        final String yaml =
                """
                name: cluster1
                connect_timeout: 1s
                cluster_type:
                  name: %s
                  typed_config:
                    "@type": type.googleapis.com/google.protobuf.StringValue
                    value: "http://127.0.0.1:%d"
                """.formatted(TestEndpointListClusterTypeFactory.NAME, endpointServerPort);
        return XdsResourceReader.fromYaml(yaml, Cluster.class);
    }

    private static Listener listenerYaml() {
        //language=YAML
        final String yaml =
                """
                name: listener1
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                    stat_prefix: http
                    rds:
                      route_config_name: route1
                      config_source:
                        ads: {}
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """;
        return XdsResourceReader.fromYaml(yaml, Listener.class);
    }

    private static RouteConfiguration routeYaml() {
        //language=YAML
        final String yaml =
                """
                name: route1
                virtual_hosts:
                - name: local_service1
                  domains: [ "*" ]
                  routes:
                  - match:
                      prefix: /
                    route:
                      cluster: cluster1
                """;
        return XdsResourceReader.fromYaml(yaml, RouteConfiguration.class);
    }

    private Bootstrap bootstrapYaml() {
        //language=YAML
        final String yaml =
                """
                dynamic_resources:
                  ads_config:
                    api_type: GRPC
                    grpc_services:
                    - envoy_grpc:
                        cluster_name: bootstrap-cluster
                  cds_config:
                    ads: {}
                  lds_config:
                    ads: {}
                static_resources:
                  clusters:
                  - name: bootstrap-cluster
                    type: STATIC
                    load_assignment:
                      cluster_name: bootstrap-cluster
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: %s
                                port_value: %s
                """.formatted(controlPlane.httpSocketAddress().getHostString(),
                              controlPlane.httpPort());
        return XdsResourceReader.fromYaml(yaml, Bootstrap.class);
    }
}
