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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncLoader;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerTlsConfig;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.netty.handler.ssl.ClientAuth;

class AuthTokenFilterTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final String ECHO_CLUSTER = "echo-cluster";
    private static final String TOKEN_SERVER_CLUSTER = "token-server";
    private static final String LISTENER_NAME = "listener1";
    private static final String ROUTE_NAME = "route1";
    private static final String BOOTSTRAP_CLUSTER_NAME = "bootstrap-cluster";
    private static final AtomicInteger tokenRequestCount = new AtomicInteger();

    @Order(1)
    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension();

    @Order(2)
    @RegisterExtension
    static final SelfSignedCertificateExtension clientCert = new SelfSignedCertificateExtension();

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
    static final ServerExtension tokenServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tlsProvider(TlsProvider.of(serverCert.tlsKeyPair()),
                           ServerTlsConfig.builder()
                                          .clientAuth(ClientAuth.REQUIRE)
                                          .tlsCustomizer(b -> b.trustManager(clientCert.certificate()))
                                          .build());
            sb.service("/token", (ctx, req) -> {
                tokenRequestCount.incrementAndGet();
                return HttpResponse.of("test-token-12345");
            });
        }
    };

    @RegisterExtension
    static final ServerExtension echoServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/echo", (ctx, req) -> {
                final String token = req.headers().get("x-auth-token");
                return HttpResponse.of(token != null ? token : "no-token");
            });
            sb.http(0);
        }
    };

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @BeforeEach
    void beforeEach() {
        tokenRequestCount.set(0);

        final Cluster echoCluster = XdsResourceReader.fromYaml("""
                name: %s
                type: EDS
                connect_timeout: 1s
                eds_cluster_config:
                  eds_config:
                    ads: {}
                """.formatted(ECHO_CLUSTER), Cluster.class);

        final Cluster tokenCluster = XdsResourceReader.fromYaml("""
                name: %s
                type: EDS
                connect_timeout: 1s
                eds_cluster_config:
                  eds_config:
                    ads: {}
                transport_socket:
                  name: envoy.transport_sockets.tls
                  typed_config:
                    "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.UpstreamTlsContext
                    common_tls_context:
                      tls_certificates:
                        - private_key:
                            filename: '%s'
                          certificate_chain:
                            filename: '%s'
                      validation_context:
                        trusted_ca:
                          filename: '%s'
                """.formatted(TOKEN_SERVER_CLUSTER,
                              clientCert.privateKeyFile().getAbsolutePath(),
                              clientCert.certificateFile().getAbsolutePath(),
                              serverCert.certificateFile().getAbsolutePath()), Cluster.class);

        final ClusterLoadAssignment echoAssignment = XdsResourceReader.fromYaml("""
                cluster_name: %s
                endpoints:
                - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: %s
                          port_value: %s
                """.formatted(ECHO_CLUSTER,
                              echoServer.httpSocketAddress().getHostString(),
                              echoServer.httpPort()), ClusterLoadAssignment.class);

        final ClusterLoadAssignment tokenAssignment = XdsResourceReader.fromYaml("""
                cluster_name: %s
                endpoints:
                - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: %s
                          port_value: %s
                """.formatted(TOKEN_SERVER_CLUSTER,
                              tokenServer.httpsSocketAddress().getHostString(),
                              tokenServer.httpsPort()), ClusterLoadAssignment.class);

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
                    - name: test.auth_token_filter
                      typed_config:
                        "@type": type.googleapis.com/google.protobuf.Empty
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
                """.formatted(ROUTE_NAME, ECHO_CLUSTER), RouteConfiguration.class);

        cache.setSnapshot(GROUP, Snapshot.create(
                ImmutableList.of(echoCluster, tokenCluster),
                ImmutableList.of(echoAssignment, tokenAssignment),
                ImmutableList.of(listener),
                ImmutableList.of(route),
                ImmutableList.of(),
                "1"));
    }

    @Test
    void tokenFetchedAndCachedAcrossRequests() {
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml());
        final AuthTokenFilterFactory filterFactory = new AuthTokenFilterFactory();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .eventExecutor(eventLoop.get())
                                                     .extensionFactory(filterFactory)
                                                     .build();
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();

            for (int i = 0; i < 3; i++) {
                final AggregatedHttpResponse response = client.get("/echo");
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                assertThat(response.contentUtf8()).isEqualTo("test-token-12345");
            }

            assertThat(tokenRequestCount.get()).isEqualTo(1);
        }
    }

    private String bootstrapYaml() {
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

    private static final class AuthTokenFilterFactory implements HttpFilterFactory {

        private static final String NAME = "test.auth_token_filter";
        private static final String TYPE_URL = "type.googleapis.com/google.protobuf.Empty";
        private static final List<String> TYPE_URLS = ImmutableList.of(TYPE_URL);

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public List<String> typeUrls() {
            return TYPE_URLS;
        }

        @Nullable
        @Override
        public XdsHttpFilter create(HttpFilter httpFilter, Any config, FactoryContext context) {
            throw new UnsupportedOperationException("use createStream()");
        }

        @Override
        public SnapshotStream<XdsHttpFilter> createStream(HttpFilter httpFilter, Any config,
                                                           FactoryContext context) {
            final SnapshotStream<ClusterSnapshot> clusterStream =
                    context.clusterStream(TOKEN_SERVER_CLUSTER);

            return clusterStream.switchMapEager(
                    AuthTokenFilterFactory::buildTokenFilter);
        }

        private static SnapshotStream<XdsHttpFilter> buildTokenFilter(
                ClusterSnapshot clusterSnapshot) {
            if (clusterSnapshot.loadBalancer() == null) {
                return SnapshotStream.just(new XdsHttpFilter() {});
            }
            final WebClient tokenClient = WebClient.of(clusterSnapshot.preprocessor());
            // we may consider caching the token across cluster updates in the future
            final AsyncLoader<String> tokenLoader = AsyncLoader
                    .<String>builder(cached -> tokenClient.get("/token")
                            .aggregate()
                            .thenApply(AggregatedHttpResponse::contentUtf8))
                    .expireAfterLoad(Duration.ofMinutes(5))
                    .build();

            return SnapshotStream.just(new CachedTokenXdsHttpFilter(tokenLoader));
        }
    }

    private static final class CachedTokenXdsHttpFilter implements XdsHttpFilter {

        private final AsyncLoader<String> tokenLoader;

        CachedTokenXdsHttpFilter(AsyncLoader<String> tokenLoader) {
            this.tokenLoader = tokenLoader;
        }

        @Override
        public HttpPreprocessor httpPreprocessor() {
            return (delegate, ctx, req) -> HttpResponse.of(
                    tokenLoader.load().thenApply(token -> {
                        ctx.setAdditionalRequestHeader("x-auth-token", token);
                        try {
                            return delegate.execute(ctx, req);
                        } catch (Exception e) {
                            return HttpResponse.ofFailure(e);
                        }
                    }));
        }
    }
}
