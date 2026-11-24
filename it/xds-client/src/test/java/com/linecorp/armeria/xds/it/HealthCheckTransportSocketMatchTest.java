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

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class HealthCheckTransportSocketMatchTest {

    @RegisterExtension
    static final XdsCertificateExtension serverCert =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    @RegisterExtension
    static final XdsCertificateExtension wrongCert =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    @RegisterExtension
    static final ServerExtension tlsServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tls(serverCert.tlsKeyPair());
            sb.service("/monitor/healthcheck", HealthCheckService.builder().build());
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    private static XdsLoadBalancer pollLoadBalancer(ListenerRoot root, String clusterName) {
        final AtomicReference<XdsLoadBalancer> lbRef = new AtomicReference<>();
        final SnapshotWatcher<ListenerSnapshot> watcher = (newSnapshot, t) -> {
            final ClusterSnapshot clusterSnapshot =
                    newSnapshot.routeSnapshot().virtualHostSnapshots().get(0).routeEntries().get(0)
                               .clusterSnapshot();
            if (clusterSnapshot != null && clusterName.equals(clusterSnapshot.xdsResource().name())) {
                lbRef.set(clusterSnapshot.loadBalancer());
            }
        };
        root.addSnapshotWatcher(watcher);
        await().untilAsserted(() -> assertThat(lbRef.get()).isNotNull());
        return lbRef.get();
    }

    @Test
    void healthCheckUsesMatchedTransportSocket() throws Exception {
        final String correctCa = base64Cert(serverCert.certificateFile());
        final String wrongCa = base64Cert(wrongCert.certificateFile());

        //language=YAML
        final String bootstrapYaml =
                """
                static_resources:
                  listeners:
                  - name: listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                        stat_prefix: http
                        route_config:
                          name: local_route
                          virtual_hosts:
                          - name: local_service
                            domains: ["*"]
                            routes:
                            - match:
                                prefix: /
                              route:
                                cluster: cluster
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                  clusters:
                  - name: cluster
                    type: STATIC
                    load_assignment:
                      cluster_name: cluster
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %d
                    transport_socket:
                      name: envoy.transport_sockets.tls
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.UpstreamTlsContext
                        common_tls_context:
                          validation_context:
                            trusted_ca:
                              inline_bytes: %s
                    transport_socket_matches:
                    - name: health_check_tls
                      match:
                        health_check: "true"
                      transport_socket:
                        name: envoy.transport_sockets.tls
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.UpstreamTlsContext
                          common_tls_context:
                            validation_context:
                              trusted_ca:
                                inline_bytes: %s
                    health_checks:
                    - http_health_check:
                        path: /monitor/healthcheck
                      timeout: 5s
                      interval: 10s
                      unhealthy_threshold: 1
                      healthy_threshold: 1
                      transport_socket_match_criteria:
                        health_check: "true"
                """.formatted(tlsServer.httpsPort(), wrongCa, correctCa);

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster");
            final List<Integer> healthyPorts =
                    loadBalancer.hostSets().get(0).healthyHostsEndpointGroup().endpoints()
                                .stream().map(Endpoint::port).collect(Collectors.toList());
            assertThat(healthyPorts).containsExactly(tlsServer.httpsPort());

            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final Endpoint endpoint = loadBalancer.selectNow(ctx);
            assertThat(endpoint).isNotNull();
            assertThat(endpoint.port()).isEqualTo(tlsServer.httpsPort());
        }
    }

    @Test
    void healthCheckFailsWithoutMatchCriteria() throws Exception {
        final String wrongCa = base64Cert(wrongCert.certificateFile());
        final String correctCa = base64Cert(serverCert.certificateFile());

        //language=YAML
        final String bootstrapYaml =
                """
                static_resources:
                  listeners:
                  - name: listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                        stat_prefix: http
                        route_config:
                          name: local_route
                          virtual_hosts:
                          - name: local_service
                            domains: ["*"]
                            routes:
                            - match:
                                prefix: /
                              route:
                                cluster: cluster
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                  clusters:
                  - name: cluster
                    type: STATIC
                    load_assignment:
                      cluster_name: cluster
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %d
                    transport_socket:
                      name: envoy.transport_sockets.tls
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.UpstreamTlsContext
                        common_tls_context:
                          validation_context:
                            trusted_ca:
                              inline_bytes: %s
                    transport_socket_matches:
                    - name: health_check_tls
                      match:
                        health_check: "true"
                      transport_socket:
                        name: envoy.transport_sockets.tls
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.UpstreamTlsContext
                          common_tls_context:
                            validation_context:
                              trusted_ca:
                                inline_bytes: %s
                    health_checks:
                    - http_health_check:
                        path: /monitor/healthcheck
                      timeout: 5s
                      interval: 10s
                      unhealthy_threshold: 1
                      healthy_threshold: 1
                """.formatted(tlsServer.httpsPort(), wrongCa, correctCa);

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster");
            // Without transport_socket_match_criteria, the health check uses the default
            // transport socket which has the wrong CA certificate, so the TLS handshake
            // fails and the endpoint remains unhealthy.
            assertThat(loadBalancer.hostSets().get(0).healthyHostsEndpointGroup().endpoints())
                    .isEmpty();
        }
    }

    private static String base64Cert(File certFile) throws Exception {
        return Base64.getEncoder().encodeToString(Files.readAllBytes(certFile.toPath()));
    }
}
