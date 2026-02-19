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

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
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
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.pgv.ValidationException;

class TlsValidationContextSdsIntegrationTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final AtomicLong version = new AtomicLong();

    @RegisterExtension
    static final ServerExtension adsServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getSecretDiscoveryServiceImpl())
                                  .build());
        }
    };

    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension("localhost");

    @RegisterExtension
    static final ServerExtension backendServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tls(serverCert.certificateFile(), serverCert.privateKeyFile());
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @RegisterExtension
    static final SelfSignedCertificateExtension otherCert = new SelfSignedCertificateExtension("localhost");

    // language=YAML
    //language=YAML
    private static final String bootstrapTemplate =
            """
            dynamic_resources:
              ads_config:
                api_type: GRPC
                grpc_services:
                  - envoy_grpc:
                      cluster_name: bootstrap-cluster
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
                              address: 127.0.0.1
                              port_value: %s
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
                        validation_context_sds_secret_config:
                          name: validation-certs
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
                        - name: local_service1
                          domains: [ "*" ]
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
    void validationContextRecoversAfterInvalidSecret() throws Exception {
        //language=YAML
        final String invalidSecretYaml =
                """
                name: validation-certs
                validation_context:
                  verify_certificate_hash:
                    - "abc"
                """;
        setSecret(invalidSecretYaml);

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml());
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
                if (t != null) {
                    errorRef.set(t);
                }
            });

            await().untilAsserted(() -> assertThat(errorRef.get()).isNotNull());
            assertThat(errorRef.get()).hasRootCauseInstanceOf(ValidationException.class);

            setSecret(validationContextSecret(serverCert.certificateFile()));
            await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());

            try (XdsHttpPreprocessor preprocessor =
                         XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
                final AggregatedHttpResponse res = fetch(preprocessor);
                assertThat(res.status()).isEqualTo(HttpStatus.OK);
            }
        }
    }

    @Test
    void validationContextRotationUpdatesTrust() throws Exception {
        setSecret(validationContextSecret(serverCert.certificateFile()));

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml());
        final AtomicInteger snapshotCount = new AtomicInteger();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotCount.incrementAndGet();
                }
            });

            await().untilAsserted(() -> assertThat(snapshotCount.get()).isGreaterThan(0));
            assertThat(fetch(preprocessor).status()).isEqualTo(HttpStatus.OK);

            final int initialCount = snapshotCount.get();
            setSecret(validationContextSecret(otherCert.certificateFile()));
            await().untilAsserted(() -> assertThat(snapshotCount.get()).isGreaterThan(initialCount));
            await().untilAsserted(() -> assertThatThrownBy(() -> fetch(preprocessor))
                    .isInstanceOf(UnprocessedRequestException.class));

            final int rotatedCount = snapshotCount.get();
            setSecret(validationContextSecret(serverCert.certificateFile()));
            await().untilAsserted(() -> assertThat(snapshotCount.get()).isGreaterThan(rotatedCount));
            await().untilAsserted(() ->
                    assertThat(fetch(preprocessor).status()).isEqualTo(HttpStatus.OK));
        }
    }

    private static String bootstrapYaml() {
        return bootstrapTemplate.formatted(adsServer.httpPort(), backendServer.httpsPort());
    }

    private static void setSecret(String secretYaml) {
        final Secret secret = XdsResourceReader.fromYaml(secretYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));
    }

    private static String validationContextSecret(File caFile) throws Exception {
        final byte[] caBytes = Files.readAllBytes(caFile.toPath());
        //language=YAML
        return """
               name: validation-certs
               validation_context:
                 trusted_ca:
                   inline_bytes: %s
               """.formatted(Base64.getEncoder().encodeToString(caBytes));
    }

    private static AggregatedHttpResponse fetch(XdsHttpPreprocessor preprocessor) {
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .connectTimeoutMillis(1000)
                                  .build()) {
            return WebClient.builder(preprocessor)
                            .factory(clientFactory)
                            .responseTimeoutMillis(3000)
                            .build()
                            .blocking()
                            .get("/");
        }
    }
}
