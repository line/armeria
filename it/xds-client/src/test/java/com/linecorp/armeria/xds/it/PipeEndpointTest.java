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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.DomainSocketAddress;
import com.linecorp.armeria.internal.testing.EnabledOnOsWithDomainSockets;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.netty.handler.ssl.ClientAuth;

@EnabledOnOsWithDomainSockets
class PipeEndpointTest {

    private static final String SOCKET_PATH =
            System.getProperty("java.io.tmpdir") + "/armeria-xds-pipe-" +
            Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".sock";

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final AtomicLong version = new AtomicLong();

    @TempDir
    static Path tempDir;

    @RegisterExtension
    @Order(0)
    static final SelfSignedCertificateExtension serverCert =
            new SelfSignedCertificateExtension("localhost");

    @RegisterExtension
    @Order(0)
    static final SelfSignedCertificateExtension clientCert =
            new SelfSignedCertificateExtension();

    @RegisterExtension
    @Order(1)
    static final ServerExtension sdsServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(DomainSocketAddress.of(tempDir.resolve("sds.sock").toString()));
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getSecretDiscoveryServiceImpl())
                                  .build());
        }
    };

    @RegisterExtension
    @Order(1)
    static final ServerExtension backendServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tls(serverCert.certificateFile(), serverCert.privateKeyFile());
            sb.tlsCustomizer(ssl -> {
                ssl.clientAuth(ClientAuth.REQUIRE);
                ssl.trustManager(clientCert.certificate());
            });
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
        }
    };

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(DomainSocketAddress.of(SOCKET_PATH));
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
        }
    };

    //language=YAML
    private static final String bootstrapTemplate =
            """
            static_resources:
              listeners:
              - name: my-listener
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
                            cluster: my-cluster
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
              clusters:
              - name: my-cluster
                type: STATIC
                load_assignment:
                  cluster_name: my-cluster
                  endpoints:
                  - lb_endpoints:
                    - endpoint:
                        address:
                          pipe:
                            path: %s
            """;

    //language=YAML
    private static final String strictDnsBootstrapTemplate =
            """
            static_resources:
              clusters:
              - name: my-cluster
                type: STRICT_DNS
                load_assignment:
                  cluster_name: my-cluster
                  endpoints:
                  - lb_endpoints:
                    - endpoint:
                        address:
                          pipe:
                            path: %s
            """;

    //language=YAML
    private static final String sdsBootstrapTemplate =
            """
            dynamic_resources:
              ads_config:
                api_type: GRPC
                grpc_services:
                - envoy_grpc:
                    cluster_name: sds-cluster
            static_resources:
              clusters:
              - name: sds-cluster
                type: STATIC
                load_assignment:
                  cluster_name: sds-cluster
                  endpoints:
                  - lb_endpoints:
                    - endpoint:
                        address:
                          pipe:
                            path: %s
              - name: my-cluster
                type: STATIC
                load_assignment:
                  cluster_name: my-cluster
                  endpoints:
                  - lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: %s
                transport_socket:
                  name: envoy.transport_sockets.tls
                  typed_config:
                    "@type": type.googleapis.com/envoy.extensions.transport_sockets\
            .tls.v3.UpstreamTlsContext
                    common_tls_context:
                      tls_certificate_sds_secret_configs:
                      - name: client-cert
                        sds_config:
                          ads: {}
                      validation_context_sds_secret_config:
                        name: server-ca
                        sds_config:
                          ads: {}
              listeners:
              - name: my-listener
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
                            cluster: my-cluster
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
            """;

    @Test
    void pipeEndpointRouted() throws Exception {
        final String bootstrapYaml = bootstrapTemplate.formatted(SOCKET_PATH);
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml, Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final String response = WebClient.builder(preprocessor)
                                             .factory(ClientFactory.insecure())
                                             .build()
                                             .blocking()
                                             .get("/hello")
                                             .contentUtf8();
            assertThat(response).isEqualTo("world");
        }
    }

    @Test
    void pipeEndpointInStrictDnsThrows() throws Exception {
        final String bootstrapYaml = strictDnsBootstrapTemplate.formatted(SOCKET_PATH);
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml, Bootstrap.class);
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        // The error fires during static-cluster initialization (before clusterRoot() is even called),
        // so the defaultSnapshotWatcher — installed before the pipeline starts — is the only
        // reliable observer.
        final SnapshotWatcher<Object> watcher = (snapshot, t) -> {
            if (t != null) {
                errorRef.set(t);
            }
        };
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .defaultSnapshotWatcher(watcher)
                                                     .build()) {
            xdsBootstrap.clusterRoot("my-cluster");
            await().untilAsserted(() ->
                assertThat(errorRef.get())
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("Pipe addresses are not supported for STRICT_DNS"));
        }
    }

    @Test
    void sdsViaControlPlanePipe() throws Exception {
        // Push both secrets atomically in a single snapshot:
        //   client-cert → the TLS key pair the xDS client presents to the backend (mTLS)
        //   server-ca   → the CA the xDS client uses to trust the backend's server cert
        final Secret clientCertSecret = tlsCertSecret("client-cert", clientCert);
        final Secret serverCaSecret = validationContextSecret("server-ca", serverCert);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(clientCertSecret, serverCaSecret),
                version.toString()));

        final String bootstrapYaml =
                sdsBootstrapTemplate.formatted(tempDir.resolve("sds.sock").toString(),
                                               backendServer.httpsPort());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml, Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {

            // Wait until the listener snapshot is fully assembled (SDS secrets resolved)
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            xdsBootstrap.listenerRoot("my-listener")
                        .addSnapshotWatcher((snapshot, t) -> {
                            if (snapshot != null) {
                                snapshotRef.set(snapshot);
                            }
                        });
            await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());
            try (ClientFactory factory = ClientFactory.builder().connectTimeoutMillis(3000).build()) {
                // mTLS request must succeed end-to-end
                final AggregatedHttpResponse response =
                        WebClient.builder(preprocessor)
                                 .factory(factory)
                                 .build()
                                 .blocking()
                                 .get("/hello");
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                assertThat(response.contentUtf8()).isEqualTo("world");
            }
        }
    }

    private static Secret tlsCertSecret(String name, SelfSignedCertificateExtension cert) {
        final String yaml = """
                name: %s
                tls_certificate:
                  private_key:
                    filename: %s
                  certificate_chain:
                    filename: %s
                """.formatted(name,
                              cert.privateKeyFile().toPath().toString(),
                              cert.certificateFile().toPath().toString());
        return XdsResourceReader.fromYaml(yaml, Secret.class);
    }

    private static Secret validationContextSecret(String name,
                                                   SelfSignedCertificateExtension cert)
            throws Exception {
        final byte[] caBytes = Files.readAllBytes(cert.certificateFile().toPath());
        final String yaml = """
                name: %s
                validation_context:
                  trusted_ca:
                    inline_bytes: %s
                """.formatted(name, Base64.getEncoder().encodeToString(caBytes));
        return XdsResourceReader.fromYaml(yaml, Secret.class);
    }
}
