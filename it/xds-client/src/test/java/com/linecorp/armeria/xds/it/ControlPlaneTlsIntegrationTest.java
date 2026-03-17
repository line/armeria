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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerTlsConfig;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
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
import io.netty.handler.ssl.ClientAuth;

class ControlPlaneTlsIntegrationTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final String CLUSTER_NAME = "cluster1";
    private static final String LISTENER_NAME = "listener1";
    private static final String ROUTE_NAME = "route1";
    private static final String BOOTSTRAP_CLUSTER_NAME = "bootstrap-cluster";

    @RegisterExtension
    @Order(0)
    static final SelfSignedCertificateExtension controlPlaneCert =
            new SelfSignedCertificateExtension("127.0.0.1");

    @RegisterExtension
    @Order(0)
    static final SelfSignedCertificateExtension clientCert =
            new SelfSignedCertificateExtension("client.example.com");

    @RegisterExtension
    @Order(1)
    static final ServerExtension controlPlaneServer = new ServerExtension() {
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
            sb.tls(controlPlaneCert.certificateFile(), controlPlaneCert.privateKeyFile());
            sb.https(0);
        }
    };

    @RegisterExtension
    @Order(2)
    static final ServerExtension mtlsControlPlaneServer = new ServerExtension() {
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
            final TlsProvider tlsProvider =
                    TlsProvider.builder()
                               .keyPair(controlPlaneCert.tlsKeyPair())
                               .trustedCertificates(clientCert.certificate())
                               .build();
            final ServerTlsConfig tlsConfig = ServerTlsConfig.builder()
                                                             .clientAuth(ClientAuth.REQUIRE)
                                                             .build();
            sb.tlsProvider(tlsProvider, tlsConfig);
            sb.https(0);
        }
    };

    @RegisterExtension
    static final ServerExtension backendServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
            sb.http(0);
        }
    };

    @BeforeEach
    void beforeEach() {
        final Cluster cluster = clusterYaml(CLUSTER_NAME);
        final ClusterLoadAssignment assignment =
                endpointYaml(CLUSTER_NAME,
                             backendServer.httpSocketAddress().getHostString(),
                             backendServer.httpPort());
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
    void controlPlaneHttpsWithTrustedCaAndSanMatch() {
        //language=YAML
        final String validationContext =
                """
                validation_context:
                  trusted_ca:
                    filename: %s
                  match_typed_subject_alt_names:
                  - san_type: IP_ADDRESS
                    matcher:
                      exact: "127.0.0.1"
                """.formatted(controlPlaneCert.certificateFile().getAbsolutePath());
        final String bootstrapYaml =
                bootstrapYaml(BOOTSTRAP_CLUSTER_NAME,
                              "127.0.0.1",
                              controlPlaneServer.httpsPort(),
                              validationContext);
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml, Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(LISTENER_NAME);
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });
            await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());

            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            assertThat(client.get("/hello").contentUtf8()).isEqualTo("world");
        }
    }

    @Test
    void controlPlaneHttpsFailsWithSanMismatch() {
        //language=YAML
        final String validationContext =
                """
                validation_context:
                  trusted_ca:
                    filename: %s
                  match_typed_subject_alt_names:
                  - san_type: IP_ADDRESS
                    matcher:
                      exact: "192.0.2.1"
                """.formatted(controlPlaneCert.certificateFile().getAbsolutePath());
        final String bootstrapYaml =
                bootstrapYaml(BOOTSTRAP_CLUSTER_NAME,
                              "127.0.0.1",
                              controlPlaneServer.httpsPort(),
                              validationContext);
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml, Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            assertThatThrownBy(() -> failingClient(preprocessor).get("/hello"))
                    .isInstanceOf(UnprocessedRequestException.class);
        }
    }

    @Test
    void controlPlaneHttpsFailsWithBadSpkiPin() throws Exception {
        final String badSpki = mutateLastChar(spkiPin(controlPlaneCert.certificate()));
        //language=YAML
        final String validationContext =
                """
                validation_context:
                  trusted_ca:
                    filename: %s
                  verify_certificate_spki:
                    - "%s"
                  match_typed_subject_alt_names:
                  - san_type: IP_ADDRESS
                    matcher:
                      exact: "127.0.0.1"
                """.formatted(controlPlaneCert.certificateFile().getAbsolutePath(), badSpki);
        final String bootstrapYaml =
                bootstrapYaml(BOOTSTRAP_CLUSTER_NAME,
                              "127.0.0.1",
                              controlPlaneServer.httpsPort(),
                              validationContext);
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml, Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            assertThatThrownBy(() -> failingClient(preprocessor).get("/hello"))
                    .isInstanceOf(UnprocessedRequestException.class);
        }
    }

    @Test
    void controlPlaneHttpsFailsWhenClientCertRequired() {
        //language=YAML
        final String validationContext =
                """
                validation_context:
                  trusted_ca:
                    filename: %s
                  match_typed_subject_alt_names:
                  - san_type: IP_ADDRESS
                    matcher:
                      exact: "127.0.0.1"
                """.formatted(controlPlaneCert.certificateFile().getAbsolutePath());
        final String bootstrapYaml =
                bootstrapYaml(BOOTSTRAP_CLUSTER_NAME,
                              "127.0.0.1",
                              mtlsControlPlaneServer.httpsPort(),
                              validationContext);
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml, Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            assertThatThrownBy(() -> failingClient(preprocessor).get("/hello"))
                    .isInstanceOf(UnprocessedRequestException.class);
        }
    }

    @Test
    void controlPlaneHttpsSucceedsWithClientCert() {
        //language=YAML
        final String commonTlsContext =
                """
                tls_certificates:
                  - private_key:
                      filename: %s
                    certificate_chain:
                      filename: %s
                validation_context:
                  trusted_ca:
                    filename: %s
                  match_typed_subject_alt_names:
                  - san_type: IP_ADDRESS
                    matcher:
                      exact: "127.0.0.1"
                """.formatted(clientCert.privateKeyFile().getAbsolutePath(),
                              clientCert.certificateFile().getAbsolutePath(),
                              controlPlaneCert.certificateFile().getAbsolutePath());
        final String bootstrapYaml =
                bootstrapYaml(BOOTSTRAP_CLUSTER_NAME,
                              "127.0.0.1",
                              mtlsControlPlaneServer.httpsPort(),
                              commonTlsContext);
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml, Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(LISTENER_NAME);
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });
            await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());

            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            assertThat(client.get("/hello").contentUtf8()).isEqualTo("world");
        }
    }

    private static Cluster clusterYaml(String name) {
        //language=YAML
        final String yaml =
                """
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

    private static String bootstrapYaml(String clusterName, String address, int port,
                                        String commonTlsContextYaml) {
        //language=YAML
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
                   transport_socket:
                     name: envoy.transport_sockets.tls
                     typed_config:
                       "@type": type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext
                       common_tls_context:
               %s
               """.formatted(clusterName, clusterName, clusterName, address, port,
                             commonTlsContextYaml.indent(10));
    }

    private static BlockingWebClient failingClient(XdsHttpPreprocessor preprocessor) {
        return WebClient.builder(preprocessor)
                        .responseTimeoutMillis(2000)
                        .build()
                        .blocking();
    }

    private static String spkiPin(X509Certificate certificate) throws CertificateException {
        final byte[] digest = sha256(certificate.getPublicKey().getEncoded());
        return Base64.getEncoder().encodeToString(digest);
    }

    private static byte[] sha256(byte[] input) throws CertificateException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new CertificateException("SHA-256 is not available.", e);
        }
    }

    private static String mutateLastChar(String value) {
        final char last = value.charAt(value.length() - 1);
        final char replacement = last == 'A' ? 'B' : 'A';
        return value.substring(0, value.length() - 1) + replacement;
    }
}
