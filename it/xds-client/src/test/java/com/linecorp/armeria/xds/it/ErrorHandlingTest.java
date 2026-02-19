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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsResourceException;
import com.linecorp.armeria.xds.XdsType;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.pgv.ValidationException;

class ErrorHandlingTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final AtomicLong version = new AtomicLong();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
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
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
        }
    };

    @RegisterExtension
    static final SelfSignedCertificateExtension certificate = new SelfSignedCertificateExtension();

    //language=YAML
    private static final String listenerYaml =
            """
                name: my-listener
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

    //language=YAML
    private static final String clusterYaml =
            """
                name: my-cluster
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
                """;

    @BeforeEach
    void beforeEach() {
        final Cluster cluster =
                XdsResourceReader.fromYaml(clusterYaml.formatted(server.httpPort()), Cluster.class);
        final Listener listener = XdsResourceReader.fromYaml(listenerYaml, Listener.class);
        version.incrementAndGet();
        final Snapshot snapshot = Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(),
                                                  ImmutableList.of(listener),
                                                  ImmutableList.of(), ImmutableList.of(), version.toString());
        cache.setSnapshot(GROUP, snapshot);
    }

    //language=YAML
    private static final String rootFailure_bootstrapYaml =
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
                                  port_value: 1234
                """;

    static Stream<Arguments> rootFailure_args() {
        final Consumer<XdsBootstrap> clusterGen = xdsBootstrap -> xdsBootstrap.clusterRoot("my-cluster");
        final Consumer<XdsBootstrap> listenerGen = xdsBootstrap -> xdsBootstrap.listenerRoot("my-listener");
        return Stream.of(Arguments.of(clusterGen, XdsType.CLUSTER, "my-cluster"),
                         Arguments.of(listenerGen, XdsType.LISTENER, "my-listener"));
    }

    @ParameterizedTest
    @MethodSource("rootFailure_args")
    void rootFailure(Consumer<XdsBootstrap> rootGenFn, XdsType type, String expectedName) throws Exception {
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(rootFailure_bootstrapYaml, Bootstrap.class);
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final var watcher = new SnapshotWatcher<>() {

            @Override
            public void onUpdate(@Nullable Object snapshot, @Nullable Throwable t) {
                if (t != null) {
                    errorRef.set(t);
                }
            }
        };
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .defaultSnapshotWatcher(watcher)
                                                     .build()) {
            rootGenFn.accept(xdsBootstrap);
            await().untilAsserted(() -> assertThat(errorRef.get()).isNotNull());
            assertThat(errorRef.get()).isInstanceOf(XdsResourceException.class);
            final XdsResourceException xdsResourceException = (XdsResourceException) errorRef.get();
            assertThat(xdsResourceException.type()).isEqualTo(type);
            assertThat(xdsResourceException.name()).isEqualTo(expectedName);
            assertThat(xdsResourceException)
                    .cause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("config source not found");
        }
    }

    //language=YAML
    private static final String adsFailureBootstrapYaml =
            """
                dynamic_resources:
                  ads_config:
                    api_type: GRPC
                    grpc_services:
                      - envoy_grpc:
                          cluster_name: unknown-cluster
                  lds_config:
                    api_config_source:
                      api_type: GRPC
                      grpc_services:
                        - envoy_grpc:
                            cluster_name: bootstrap-cluster
                  cds_config:
                    api_config_source:
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
                """;

    //language=YAML
    private static final String rdsListenerYaml =
            """
                name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                    stat_prefix: my-listener
                    rds:
                      route_config_name: "unknown-route"
                      config_source:
                        ads: {}
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """;

    //language=YAML
    private static final String edsClusterYaml =
            """
                name: my-cluster
                eds_cluster_config:
                  service_name: unknown-cluster
                  eds_config:
                    ads: {}
                """;

    static Stream<Arguments> discoveryFailure_args() {
        final Consumer<XdsBootstrap> clusterGen = xdsBootstrap -> xdsBootstrap.clusterRoot("my-cluster");
        final Consumer<XdsBootstrap> listenerGen = xdsBootstrap -> xdsBootstrap.listenerRoot("my-listener");
        return Stream.of(Arguments.of(clusterGen, XdsType.ENDPOINT, "unknown-cluster"),
                         Arguments.of(listenerGen, XdsType.ROUTE, "unknown-route"));
    }

    @ParameterizedTest
    @MethodSource("discoveryFailure_args")
    void discoveryFailure(Consumer<XdsBootstrap> rootGenFn, XdsType type,
                          String expectedName) throws Exception {
        final String bootstrapStr = adsFailureBootstrapYaml.formatted(server.httpPort());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);
        final Cluster cluster = XdsResourceReader.fromYaml(edsClusterYaml, Cluster.class);
        final Listener listener = XdsResourceReader.fromYaml(rdsListenerYaml, Listener.class);
        version.incrementAndGet();
        final Snapshot snapshot = Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(),
                                                  ImmutableList.of(listener), ImmutableList.of(),
                                                  ImmutableList.of(), version.toString());
        cache.setSnapshot(GROUP, snapshot);

        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final var watcher = new SnapshotWatcher<>() {

            @Override
            public void onUpdate(@Nullable Object snapshot, @Nullable Throwable t) {
                if (t != null) {
                    errorRef.set(t);
                }
            }
        };
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .defaultSnapshotWatcher(watcher)
                                                     .build()) {
            rootGenFn.accept(xdsBootstrap);

            await().untilAsserted(() -> assertThat(errorRef.get()).isNotNull());
            assertThat(errorRef.get()).isInstanceOf(XdsResourceException.class);
            final XdsResourceException xdsResourceException = (XdsResourceException) errorRef.get();
            assertThat(xdsResourceException.type()).isEqualTo(type);
            assertThat(xdsResourceException.name()).isEqualTo(expectedName);
            assertThat(xdsResourceException)
                    .cause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot find");
        }
    }

    //language=YAML
    private static final String malformedPrimaryStaticClusterBootstrapYaml =
            """
                static_resources:
                  clusters:
                    - type: STATIC
                      load_assignment:
                        cluster_name: bootstrap-cluster
                """;

    //language=YAML
    private static final String malformedSecondaryStaticClusterBootstrapYaml =
            """
                static_resources:
                  clusters:
                    - type: EDS
                      eds_cluster_config:
                        eds_config:
                          ads: {}
            """;

    //language=YAML
    private static final String malformedStaticListenerBootstrapYaml =
            """
                static_resources:
                  listeners:
                    - name: my-listener
                      api_listener:
                        api_listener:
                          "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
            .v3.HttpConnectionManager
            """;

    public static Stream<Arguments> staticResourceValidationFailure_args() {
        return Stream.of(
                Arguments.of(malformedPrimaryStaticClusterBootstrapYaml,
                             "name: length must be at least 1 but got: 0"),
                Arguments.of(malformedSecondaryStaticClusterBootstrapYaml,
                             "name: length must be at least 1 but got: 0"),
                Arguments.of(malformedStaticListenerBootstrapYaml,
                             "stat_prefix: length must be at least 1 but got: 0")
        );
    }

    @ParameterizedTest
    @MethodSource("staticResourceValidationFailure_args")
    void staticResourceValidationFailure(String bootstrapYaml, String errorMsg) throws Exception {
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml, Bootstrap.class);
        assertThatThrownBy(() -> {
            try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
                // do nothing
            }
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(errorMsg);
    }

    //language=YAML
    private static final String malformedListenerYaml =
            """
                name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
            .v3.HttpConnectionManager
            """;

    //language=YAML
    private static final String listenerRdsYaml =
            """
                name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                    stat_prefix: my-listener
                    rds:
                      route_config_name: my-route
                      config_source:
                        ads: {}
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """;

    //language=YAML
    private static final String malformedRouteYaml =
            """
              name: my-route
              virtual_hosts:
              - name: local_service1
                domains: []
            """;

    //language=yaml
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
    private static final String malformedClusterYaml =
            """
              name: my-cluster
              connect_timeout: -1s
            """;

    //language=YAML
    private static final String normalClusterYaml =
            """
                name: my-cluster
                eds_cluster_config:
                  service_name: my-cluster
                  eds_config:
                    ads: {}
                """;

    //language=YAML
    private static final String malformedEndpointYaml =
            """
            cluster_name: my-cluster
            endpoints:
            - lb_endpoints:
              - endpoint:
                  address:
                    socket_address:
                      address: 127.0.0.1
                      port_value: 1234
              priority: 1234
            """;

    //language=YAML
    private static final String adsBootstrapYaml =
            """
                dynamic_resources:
                  ads_config:
                    api_type: GRPC
                    grpc_services:
                      - envoy_grpc:
                          cluster_name: bootstrap-cluster
                  lds_config:
                    api_config_source:
                      api_type: GRPC
                      grpc_services:
                        - envoy_grpc:
                            cluster_name: bootstrap-cluster
                  cds_config:
                    api_config_source:
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
                """;

    private static Stream<Arguments> listenerRootDynamicResourceValidationFailure_args() {
        final Listener listener1 = XdsResourceReader.fromYaml(malformedListenerYaml, Listener.class);
        final Snapshot snapshot1 = Snapshot.create(ImmutableList.of(), ImmutableList.of(),
                                                   ImmutableList.of(listener1), ImmutableList.of(),
                                                   ImmutableList.of(), version.toString());

        final Listener listener2 = XdsResourceReader.fromYaml(listenerRdsYaml, Listener.class);
        final RouteConfiguration route2 =
                XdsResourceReader.fromYaml(malformedRouteYaml, RouteConfiguration.class);
        final Snapshot snapshot2 = Snapshot.create(ImmutableList.of(), ImmutableList.of(),
                                                   ImmutableList.of(listener2), ImmutableList.of(route2),
                                                   ImmutableList.of(), version.toString());

        final Listener listener3 = XdsResourceReader.fromYaml(listenerRdsYaml, Listener.class);
        final RouteConfiguration route3 =
                XdsResourceReader.fromYaml(routeYaml, RouteConfiguration.class);
        final Cluster cluster3 = XdsResourceReader.fromYaml(malformedClusterYaml, Cluster.class);
        final Snapshot snapshot3 = Snapshot.create(ImmutableList.of(cluster3), ImmutableList.of(),
                                                   ImmutableList.of(listener3), ImmutableList.of(route3),
                                                   ImmutableList.of(), version.toString());

        final Listener listener4 = XdsResourceReader.fromYaml(listenerRdsYaml, Listener.class);
        final RouteConfiguration route4 =
                XdsResourceReader.fromYaml(routeYaml, RouteConfiguration.class);
        final Cluster cluster4 = XdsResourceReader.fromYaml(normalClusterYaml, Cluster.class);
        final ClusterLoadAssignment endpoint4 = XdsResourceReader.fromYaml(malformedEndpointYaml,
                                                                           ClusterLoadAssignment.class);
        final Snapshot snapshot4 = Snapshot.create(ImmutableList.of(cluster4), ImmutableList.of(endpoint4),
                                                   ImmutableList.of(listener4), ImmutableList.of(route4),
                                                   ImmutableList.of(), version.toString());
        return Stream.of(
                Arguments.of(snapshot1, XdsType.LISTENER, "my-listener",
                             "stat_prefix: length must be at least 1 but got: 0"),
                Arguments.of(snapshot2, XdsType.ROUTE, "my-route",
                             "domains: must have at least 1 items"),
                Arguments.of(snapshot3, XdsType.CLUSTER, "my-cluster",
                             "connect_timeout: must be greater than"),
                Arguments.of(snapshot4, XdsType.ENDPOINT, "my-cluster",
                             "priority: must be less than or equal to 128")
        );
    }

    @ParameterizedTest
    @MethodSource("listenerRootDynamicResourceValidationFailure_args")
    void listenerRootDynamicResourceValidationFailure(Snapshot snapshot, XdsType type, String name,
                                                      String errorMsg) throws Exception {
        final String bootstrapStr = adsBootstrapYaml.formatted(server.httpPort());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);

        version.incrementAndGet();
        cache.setSnapshot(GROUP, snapshot);

        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final var watcher = new SnapshotWatcher<>() {

            @Override
            public void onUpdate(@Nullable Object snapshot, @Nullable Throwable t) {
                if (t != null) {
                    errorRef.set(t);
                }
            }
        };
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .defaultSnapshotWatcher(watcher)
                                                     .build()) {
            xdsBootstrap.listenerRoot("my-listener");
            await().untilAsserted(() -> assertThat(errorRef.get()).isNotNull());
            assertThat(errorRef.get()).isInstanceOf(XdsResourceException.class);
            final XdsResourceException xdsResourceException = (XdsResourceException) errorRef.get();
            assertThat(xdsResourceException.type()).isEqualTo(type);
            assertThat(xdsResourceException.name()).isEqualTo(name);
            assertThat(xdsResourceException).cause()
                                            .isInstanceOf(IllegalArgumentException.class)
                                            .cause()
                                            .isInstanceOf(ValidationException.class)
                                            .hasMessageContaining(errorMsg);
        }
    }

    private static Stream<Arguments> clusterRootDynamicResourceValidationFailure_args() {

        final Cluster cluster1 = XdsResourceReader.fromYaml(malformedClusterYaml, Cluster.class);
        final Snapshot snapshot1 = Snapshot.create(ImmutableList.of(cluster1), ImmutableList.of(),
                                                   ImmutableList.of(), ImmutableList.of(),
                                                   ImmutableList.of(), version.toString());

        final Cluster cluster2 = XdsResourceReader.fromYaml(normalClusterYaml, Cluster.class);
        final ClusterLoadAssignment endpoint2 = XdsResourceReader.fromYaml(malformedEndpointYaml,
                                                                           ClusterLoadAssignment.class);
        final Snapshot snapshot2 = Snapshot.create(ImmutableList.of(cluster2), ImmutableList.of(endpoint2),
                                                   ImmutableList.of(), ImmutableList.of(),
                                                   ImmutableList.of(), version.toString());
        return Stream.of(
                Arguments.of(snapshot1, XdsType.CLUSTER, "my-cluster",
                             "connect_timeout: must be greater than"),
                Arguments.of(snapshot2, XdsType.ENDPOINT, "my-cluster",
                             "priority: must be less than or equal to 128")
        );
    }

    @ParameterizedTest
    @MethodSource("clusterRootDynamicResourceValidationFailure_args")
    void clusterRootDynamicResourceValidationFailure(Snapshot snapshot, XdsType type, String name,
                                                     String errorMsg) throws Exception {
        final String bootstrapStr = adsBootstrapYaml.formatted(server.httpPort());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);

        version.incrementAndGet();
        cache.setSnapshot(GROUP, snapshot);

        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final var watcher = new SnapshotWatcher<>() {

            @Override
            public void onUpdate(@Nullable Object snapshot, @Nullable Throwable t) {
                if (t != null) {
                    errorRef.set(t);
                }
            }
        };
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .defaultSnapshotWatcher(watcher)
                                                     .build()) {
            xdsBootstrap.clusterRoot("my-cluster");
            await().untilAsserted(() -> assertThat(errorRef.get()).isNotNull());
            assertThat(errorRef.get()).isInstanceOf(XdsResourceException.class);
            final XdsResourceException xdsResourceException = (XdsResourceException) errorRef.get();
            assertThat(xdsResourceException.type()).isEqualTo(type);
            assertThat(xdsResourceException.name()).isEqualTo(name);
            assertThat(xdsResourceException).cause()
                                            .isInstanceOf(IllegalArgumentException.class)
                                            .cause()
                                            .isInstanceOf(ValidationException.class)
                                            .hasMessageContaining(errorMsg);
        }
    }

    //language=YAML
    private static final String sdsBootstrapYaml =
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
                                  port_value: 8080
                      transport_socket:
                        name: envoy.transport_sockets.tls
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.transport_sockets\
            .tls.v3.UpstreamTlsContext
                          common_tls_context:
                            tls_certificate_sds_secret_configs:
                              - name: my-cert
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
    void sdsInvalidCertificateFile(@TempDir File tempDir) throws Exception {
        final File invalidCertFile = new File(tempDir, "invalid.pem");
        Files.writeString(invalidCertFile.toPath(), "this is not a valid certificate");

        final String secretYaml =
                """
                name: my-cert
                tls_certificate:
                  private_key:
                    filename: %s
                  certificate_chain:
                    filename: %s
                """.formatted(certificate.privateKeyFile().toPath().toString(),
                              invalidCertFile.getAbsolutePath());
        final Secret secret = XdsResourceReader.fromYaml(secretYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr = sdsBootstrapYaml.formatted(server.httpPort());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);

        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final var watcher = new SnapshotWatcher<>() {
            @Override
            public void onUpdate(@Nullable Object snapshot, @Nullable Throwable t) {
                if (t != null) {
                    errorRef.set(t);
                }
            }
        };
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .defaultSnapshotWatcher(watcher)
                                                     .build()) {
            xdsBootstrap.listenerRoot("my-listener");
            await().untilAsserted(() -> assertThat(errorRef.get()).isNotNull());
            assertThat(errorRef.get()).isInstanceOf(XdsResourceException.class);
            final XdsResourceException xdsResourceException = (XdsResourceException) errorRef.get();
            assertThat(xdsResourceException.type()).isEqualTo(XdsType.SECRET);
            assertThat(xdsResourceException.name()).isEqualTo("my-cert");
            assertThat(xdsResourceException).cause().isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void sdsInvalidPrivateKeyFile(@TempDir File tempDir) throws Exception {
        final File invalidKeyFile = new File(tempDir, "invalid.key");
        Files.writeString(invalidKeyFile.toPath(), "this is not a valid private key");

        final String secretYaml =
                """
                name: my-cert
                tls_certificate:
                  private_key:
                    filename: %s
                  certificate_chain:
                    filename: %s
                """.formatted(invalidKeyFile.getAbsolutePath(),
                              certificate.certificateFile().toPath().toString());
        final Secret secret = XdsResourceReader.fromYaml(secretYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr = sdsBootstrapYaml.formatted(server.httpPort());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);

        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final var watcher = new SnapshotWatcher<>() {
            @Override
            public void onUpdate(@Nullable Object snapshot, @Nullable Throwable t) {
                if (t != null) {
                    errorRef.set(t);
                }
            }
        };
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .defaultSnapshotWatcher(watcher)
                                                     .build()) {
            xdsBootstrap.listenerRoot("my-listener");
            await().untilAsserted(() -> assertThat(errorRef.get()).isNotNull());
            assertThat(errorRef.get()).isInstanceOf(XdsResourceException.class);
            final XdsResourceException xdsResourceException = (XdsResourceException) errorRef.get();
            assertThat(xdsResourceException.type()).isEqualTo(XdsType.SECRET);
            assertThat(xdsResourceException.name()).isEqualTo("my-cert");
            assertThat(xdsResourceException).cause().isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void sdsMissingCertificateFile(@TempDir File tempDir) throws Exception {
        final File missingFile = new File(tempDir, "nonexistent.pem");

        final String secretYaml =
                """
                name: my-cert
                tls_certificate:
                  private_key:
                    filename: %s
                  certificate_chain:
                    filename: %s
                """.formatted(certificate.privateKeyFile().toPath().toString(),
                              missingFile.getAbsolutePath());
        final Secret secret = XdsResourceReader.fromYaml(secretYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr = sdsBootstrapYaml.formatted(server.httpPort());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);

        final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            xdsBootstrap.listenerRoot("my-listener").addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().during(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(snapshotRef.get()).isNull();
            });

            Files.writeString(missingFile.toPath(), Files.readString(certificate.certificateFile().toPath()));

            await().untilAsserted(() -> {
                assertThat(snapshotRef.get()).isNotNull();
            });
        }
    }

    @Test
    void sdsMissingSecretName() throws Exception {
        final String secretYaml =
                """
                name: wrong-cert-name
                tls_certificate:
                  private_key:
                    filename: %s
                  certificate_chain:
                    filename: %s
                """.formatted(certificate.privateKeyFile().toPath().toString(),
                              certificate.certificateFile().toPath().toString());
        final Secret secret = XdsResourceReader.fromYaml(secretYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr = sdsBootstrapYaml.formatted(server.httpPort());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);

        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            xdsBootstrap.listenerRoot("my-listener").addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
                if (t != null) {
                    errorRef.set(t);
                }
            });
            await().during(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                assertThat(snapshotRef.get()).isNull();
            });

            final String correctSecretYaml =
                    """
                    name: my-cert
                    tls_certificate:
                      private_key:
                        filename: %s
                      certificate_chain:
                        filename: %s
                    """.formatted(certificate.privateKeyFile().toPath().toString(),
                                  certificate.certificateFile().toPath().toString());
            final Secret correctSecret = XdsResourceReader.fromYaml(correctSecretYaml, Secret.class);
            version.incrementAndGet();
            cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                     ImmutableList.of(), ImmutableList.of(correctSecret),
                                                     version.toString()));

            await().untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                assertThat(snapshotRef.get()).isNotNull();
            });
        }
    }
}
