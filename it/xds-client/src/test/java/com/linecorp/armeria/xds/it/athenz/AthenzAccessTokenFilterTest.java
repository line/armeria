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

package com.linecorp.armeria.xds.it.athenz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.URI;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.athenz.AthenzDocker;
import com.linecorp.armeria.server.athenz.AthenzExtension;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;
import com.linecorp.armeria.xds.it.XdsResourceReader;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

@EnabledIfDockerAvailable
class AthenzAccessTokenFilterTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final String CLUSTER_NAME = "cluster1";
    private static final String LISTENER_NAME = "listener1";
    private static final String ROUTE_NAME = "route1";
    private static final String BOOTSTRAP_CLUSTER_NAME = "bootstrap-cluster";
    private static final String ZTS_CLUSTER_NAME = "zts-cluster";

    @RegisterExtension
    @Order(1)
    static final AthenzExtension athenz =
            new AthenzExtension(new File("gen-src/test/resources/docker/docker-compose.yml"));

    @RegisterExtension
    @Order(2)
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
            sb.http(0);
        }
    };

    @RegisterExtension
    @Order(3)
    static final ServerExtension echoServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/echo-auth", (ctx, req) -> {
                final String auth = req.headers().get("authorization");
                return HttpResponse.of(auth != null ? auth : "no-auth");
            });
            sb.http(0);
        }
    };

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void tokenInjectedIntoAuthorizationHeader() {
        pushXdsConfig(listener());

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.get("/echo-auth");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).startsWith("Bearer ");
        }
    }

    @Test
    void existingHeaderOverwrittenByFilter() {
        pushXdsConfig(listener());

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.prepare()
                    .get("/echo-auth")
                    .header("authorization", "Bearer existingToken")
                    .execute();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).startsWith("Bearer ");
        }
    }

    private void pushXdsConfig(Listener listenerResource) {
        final Cluster cluster = XdsResourceReader.fromYaml("""
                name: %s
                type: EDS
                connect_timeout: 1s
                eds_cluster_config:
                  eds_config:
                    ads: {}
                """.formatted(CLUSTER_NAME), Cluster.class);

        final ClusterLoadAssignment assignment = XdsResourceReader.fromYaml("""
                cluster_name: %s
                endpoints:
                - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: %s
                          port_value: %s
                """.formatted(CLUSTER_NAME,
                              echoServer.httpSocketAddress().getHostString(),
                              echoServer.httpPort()), ClusterLoadAssignment.class);

        final RouteConfiguration route = XdsResourceReader.fromYaml("""
                name: %s
                virtual_hosts:
                - name: local_service
                  domains: [ "*" ]
                  routes:
                  - match:
                      prefix: /
                    route:
                      cluster: %s
                """.formatted(ROUTE_NAME, CLUSTER_NAME), RouteConfiguration.class);

        cache.setSnapshot(GROUP, Snapshot.create(
                ImmutableList.of(cluster),
                ImmutableList.of(assignment),
                ImmutableList.of(listenerResource),
                ImmutableList.of(route),
                ImmutableList.of(),
                String.valueOf(System.nanoTime())));
    }

    private static Listener listener() {
        return XdsResourceReader.fromYaml("""
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
                    - name: athenz.access_token
                      typed_config:
                        "@type": type.googleapis.com/com.linecorp.armeria.xds.api.AthenzFilterConfig
                        zts_cluster_name: "%s"
                        access_token_target:
                          target_domain: "%s"
                          target_roles: ["%s"]
                          syntax_version: 1
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """.formatted(LISTENER_NAME, ROUTE_NAME, ZTS_CLUSTER_NAME,
                              AthenzDocker.TEST_DOMAIN_NAME, AthenzDocker.USER_ROLE),
                Listener.class);
    }

    private static final String ATHENZ_RESOURCES = "gen-src/test/resources";

    private String bootstrapYaml() {
        final URI ztsUri = athenz.ztsUri();
        final String serviceCertFile =
                ATHENZ_RESOURCES + AthenzDocker.ATHENZ_CERTS + AthenzDocker.TEST_SERVICE + "/cert.pem";
        final String serviceKeyFile =
                ATHENZ_RESOURCES + AthenzDocker.ATHENZ_CERTS + AthenzDocker.TEST_SERVICE + "/key.pem";
        final String caCertFile = ATHENZ_RESOURCES + AthenzDocker.CA_CERT_FILE;

        return """
                dynamic_resources:
                  ads_config:
                    api_type: GRPC
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
                    transport_socket:
                      name: envoy.transport_sockets.tls
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.UpstreamTlsContext
                        common_tls_context:
                          tls_certificates:
                          - certificate_chain:
                              filename: '%s'
                            private_key:
                              filename: '%s'
                          validation_context:
                            trusted_ca:
                              filename: '%s'
                """.formatted(
                        BOOTSTRAP_CLUSTER_NAME,
                        BOOTSTRAP_CLUSTER_NAME, BOOTSTRAP_CLUSTER_NAME,
                        controlPlane.httpSocketAddress().getHostString(),
                        controlPlane.httpPort(),
                        ZTS_CLUSTER_NAME, ZTS_CLUSTER_NAME,
                        ztsUri.getHost(),
                        ztsUri.getPort(),
                        serviceCertFile,
                        serviceKeyFile,
                        caCertFile);
    }
}
