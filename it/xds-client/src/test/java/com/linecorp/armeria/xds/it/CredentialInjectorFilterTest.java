/*
 * Copyright 2025 LY Corporation
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
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
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;

class CredentialInjectorFilterTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final String CLUSTER_NAME = "cluster1";
    private static final String LISTENER_NAME = "listener1";
    private static final String ROUTE_NAME = "route1";
    private static final String BOOTSTRAP_CLUSTER_NAME = "bootstrap-cluster";

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
                                  .addService(v3DiscoveryServer.getSecretDiscoveryServiceImpl())
                                  .build());
            sb.http(0);
        }
    };

    @RegisterExtension
    static final ServerExtension echoServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            // Returns the value of the Authorization header, or "no-auth" if absent
            sb.service("/echo-auth", (ctx, req) -> {
                final String auth = req.headers().get("authorization");
                return HttpResponse.of(auth != null ? auth : "no-auth");
            });
            sb.service("/echo-custom", (ctx, req) -> {
                final String custom = req.headers().get("x-custom-auth");
                return HttpResponse.of(custom != null ? custom : "no-auth");
            });
            sb.http(0);
        }
    };

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @BeforeEach
    void beforeEach() {
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

        final Listener listener = XdsResourceReader.fromYaml("""
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
                    - name: envoy.filters.http.credential_injector
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http\
                .credential_injector.v3.CredentialInjector
                        overwrite: true
                        credential:
                          name: generic_credential
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.http\
                .injected_credentials.generic.v3.Generic
                            credential:
                              name: my-credential
                            header: authorization
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """.formatted(LISTENER_NAME, ROUTE_NAME), Listener.class);

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
                ImmutableList.of(listener),
                ImmutableList.of(route),
                ImmutableList.of(),
                "1"));
    }

    @Test
    void credentialInjectedIntoAuthorizationHeader() {
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapWithSecret(
                "Bearer mySecretToken"));
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.get("/echo-auth");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("Bearer mySecretToken");
        }
    }

    @Test
    void noCredentialWithAllowWithoutCredentialFalseReturns401() {
        // Use a listener with allow_request_without_credential=false and an empty secret
        cache.setSnapshot(GROUP, Snapshot.create(
                cache.getSnapshot(GROUP).clusters().resources().values().stream().toList(),
                cache.getSnapshot(GROUP).endpoints().resources().values().stream().toList(),
                ImmutableList.of(listenerWithConfig(false, true)),
                cache.getSnapshot(GROUP).routes().resources().values().stream().toList(),
                ImmutableList.of(),
                "2"));

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapWithEmptySecret());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.get("/echo-auth");
            assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void noCredentialWithAllowWithoutCredentialTruePassesThrough() {
        cache.setSnapshot(GROUP, Snapshot.create(
                cache.getSnapshot(GROUP).clusters().resources().values().stream().toList(),
                cache.getSnapshot(GROUP).endpoints().resources().values().stream().toList(),
                ImmutableList.of(listenerWithConfig(true, true)),
                cache.getSnapshot(GROUP).routes().resources().values().stream().toList(),
                ImmutableList.of(),
                "3"));

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapWithEmptySecret());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.get("/echo-auth");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("no-auth");
        }
    }

    @Test
    void overwriteFalsePreservesExistingHeader() {
        // Use a listener with overwrite=false
        cache.setSnapshot(GROUP, Snapshot.create(
                cache.getSnapshot(GROUP).clusters().resources().values().stream().toList(),
                cache.getSnapshot(GROUP).endpoints().resources().values().stream().toList(),
                ImmutableList.of(listenerWithConfig(false, false)),
                cache.getSnapshot(GROUP).routes().resources().values().stream().toList(),
                ImmutableList.of(),
                "4"));

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(
                bootstrapWithSecret("Bearer newToken"));
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.prepare()
                    .get("/echo-auth")
                    .header("authorization", "Bearer existingToken")
                    .execute();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            // overwrite=false → existing header preserved
            assertThat(response.contentUtf8()).isEqualTo("Bearer existingToken");
        }
    }

    @Test
    void customHeaderName() {
        // Use a listener with custom header name
        cache.setSnapshot(GROUP, Snapshot.create(
                cache.getSnapshot(GROUP).clusters().resources().values().stream().toList(),
                cache.getSnapshot(GROUP).endpoints().resources().values().stream().toList(),
                ImmutableList.of(listenerWithCustomHeader("x-custom-auth")),
                cache.getSnapshot(GROUP).routes().resources().values().stream().toList(),
                ImmutableList.of(),
                "5"));

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(
                bootstrapWithSecret("Basic dXNlcjpwYXNz"));
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.get("/echo-custom");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("Basic dXNlcjpwYXNz");
        }
    }

    private Listener listenerWithConfig(boolean allowWithoutCredential, boolean overwrite) {
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
                    - name: envoy.filters.http.credential_injector
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http\
                .credential_injector.v3.CredentialInjector
                        overwrite: %s
                        allow_request_without_credential: %s
                        credential:
                          name: generic_credential
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.http\
                .injected_credentials.generic.v3.Generic
                            credential:
                              name: my-credential
                            header: authorization
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """.formatted(LISTENER_NAME, ROUTE_NAME, overwrite, allowWithoutCredential),
                Listener.class);
    }

    private Listener listenerWithCustomHeader(String headerName) {
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
                    - name: envoy.filters.http.credential_injector
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http\
                .credential_injector.v3.CredentialInjector
                        overwrite: true
                        credential:
                          name: generic_credential
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.http\
                .injected_credentials.generic.v3.Generic
                            credential:
                              name: my-credential
                            header: %s
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """.formatted(LISTENER_NAME, ROUTE_NAME, headerName), Listener.class);
    }

    private String bootstrapWithSecret(String credentialValue) {
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
                  secrets:
                  - name: my-credential
                    generic_secret:
                      secret:
                        inline_string: "%s"
                """.formatted(BOOTSTRAP_CLUSTER_NAME,
                              BOOTSTRAP_CLUSTER_NAME, BOOTSTRAP_CLUSTER_NAME,
                              controlPlane.httpSocketAddress().getHostString(),
                              controlPlane.httpPort(),
                              credentialValue);
    }

    private String bootstrapWithEmptySecret() {
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
                  secrets:
                  - name: my-credential
                    generic_secret:
                      secret:
                        inline_string: ""
                """.formatted(BOOTSTRAP_CLUSTER_NAME,
                              BOOTSTRAP_CLUSTER_NAME, BOOTSTRAP_CLUSTER_NAME,
                              controlPlane.httpSocketAddress().getHostString(),
                              controlPlane.httpPort());
    }

    @Test
    void credentialFetchedViaSds() {
        // Push a generic_secret to the control plane via SDS
        final Secret secret = XdsResourceReader.fromYaml("""
                name: my-credential
                generic_secret:
                  secret:
                    inline_string: "Bearer sdsToken123"
                """, Secret.class);

        // Use a listener whose credential_injector has sds_config: ads: {}
        final Listener sdsListener = XdsResourceReader.fromYaml("""
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
                    - name: envoy.filters.http.credential_injector
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http\
                .credential_injector.v3.CredentialInjector
                        overwrite: true
                        credential:
                          name: generic_credential
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.http\
                .injected_credentials.generic.v3.Generic
                            credential:
                              name: my-credential
                              sds_config:
                                ads: {}
                            header: authorization
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """.formatted(LISTENER_NAME, ROUTE_NAME), Listener.class);

        cache.setSnapshot(GROUP, Snapshot.create(
                cache.getSnapshot(GROUP).clusters().resources().values().stream().toList(),
                cache.getSnapshot(GROUP).endpoints().resources().values().stream().toList(),
                ImmutableList.of(sdsListener),
                cache.getSnapshot(GROUP).routes().resources().values().stream().toList(),
                ImmutableList.of(secret),
                "6"));

        // Bootstrap without static secrets — credential comes from SDS
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapWithoutSecrets());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.get("/echo-auth");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("Bearer sdsToken123");
        }
    }

    @Test
    void credentialFromFile(@TempDir Path tempDir) throws IOException {
        final Path credentialFile = tempDir.resolve("credential.txt");
        Files.writeString(credentialFile, "Bearer fileToken456");

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(
                bootstrapWithFileSecret(credentialFile.toAbsolutePath().toString()));
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.get("/echo-auth");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("Bearer fileToken456");
        }
    }

    private String bootstrapWithFileSecret(String filePath) {
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
                  secrets:
                  - name: my-credential
                    generic_secret:
                      secret:
                        filename: "%s"
                """.formatted(BOOTSTRAP_CLUSTER_NAME,
                              BOOTSTRAP_CLUSTER_NAME, BOOTSTRAP_CLUSTER_NAME,
                              controlPlane.httpSocketAddress().getHostString(),
                              controlPlane.httpPort(),
                              filePath);
    }

    private String bootstrapWithoutSecrets() {
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
                """.formatted(BOOTSTRAP_CLUSTER_NAME,
                              BOOTSTRAP_CLUSTER_NAME, BOOTSTRAP_CLUSTER_NAME,
                              controlPlane.httpSocketAddress().getHostString(),
                              controlPlane.httpPort());
    }
}
