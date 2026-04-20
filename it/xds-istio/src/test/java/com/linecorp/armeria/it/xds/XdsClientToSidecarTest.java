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
package com.linecorp.armeria.it.xds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.it.istio.testing.EnabledIfDockerAvailable;
import com.linecorp.armeria.it.istio.testing.IstioClusterExtension;
import com.linecorp.armeria.it.istio.testing.IstioPodTest;
import com.linecorp.armeria.it.istio.testing.IstioServerExtension;
import com.linecorp.armeria.xds.ClusterRoot;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.listener.v3.Listener;

@EnabledIfDockerAvailable
class XdsClientToSidecarTest {

    private static final Logger logger = LoggerFactory.getLogger(XdsClientToSidecarTest.class);

    @RegisterExtension
    @Order(1)
    static IstioClusterExtension cluster = new IstioClusterExtension();

    @RegisterExtension
    @Order(2)
    static IstioServerExtension server =
            new IstioServerExtension("echo-server", 8080, EchoConfigurator.class);

    @IstioPodTest
    void clusterLoad() throws Exception {
        final Bootstrap parsedBootstrap = loadParsedBootstrap();
        final String clusterName = "outbound|8080||echo-server.default.svc.cluster.local";

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(parsedBootstrap);
             ClusterRoot clusterRoot = xdsBootstrap.clusterRoot(clusterName)) {
            final AtomicReference<ClusterSnapshot> snapshotRef = new AtomicReference<>();
            final AtomicReference<Throwable> errorRef = new AtomicReference<>();
            clusterRoot.addSnapshotWatcher((snapshot, t) -> {
                logger.info("Cluster snapshot: {}, t: ", snapshot, t);
                if (snapshot != null) {
                    snapshotRef.compareAndSet(null, snapshot);
                }
                if (t != null) {
                    errorRef.compareAndSet(null, t);
                }
            });

            await().untilAsserted(() -> {
                final Throwable t = errorRef.get();
                if (t != null) {
                    throw new AssertionError("Failed to load cluster snapshot", t);
                }
                assertThat(snapshotRef.get()).isNotNull();
            });
        }
    }

    @IstioPodTest
    void clusterRequest() throws Exception {
        final String clusterName = "outbound|8080||echo-server.default.svc.cluster.local";
        final String listenerName = "armeria-test-cluster-listener";
        final Listener listener = listenerWithStaticRoute(listenerName, clusterName);

        final Bootstrap parsedBootstrap = loadParsedBootstrap();
        final Bootstrap.StaticResources staticResources =
                parsedBootstrap.getStaticResources().toBuilder()
                               .addListeners(listener)
                               .build();
        final Bootstrap bootstrap = parsedBootstrap.toBuilder()
                                                   .setStaticResources(staticResources)
                                                   .build();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(listenerName, xdsBootstrap)) {
            final WebClient client = WebClient.builder(preprocessor)
                                              .decorator(LoggingClient.newDecorator())
                                              .build();
            final AggregatedHttpResponse response = client.get("/echo").aggregate().join();
            logger.info("Response: {}", response);
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("hello");
        }
    }

    @IstioPodTest
    void routeLoad() throws Exception {
        final Bootstrap parsedBootstrap = loadParsedBootstrap();

        final String listenerName = "armeria-test-ads-listener";
        final String routeConfigName = "echo-server.default.svc.cluster.local:8080";
        final Listener listener = listenerWithRdsAds(listenerName, routeConfigName);

        final Bootstrap.StaticResources staticResources = parsedBootstrap.getStaticResources().toBuilder()
                                                                         .addListeners(listener)
                                                                         .build();

        final Bootstrap.DynamicResources dynamicResources = parsedBootstrap.getDynamicResources();
        final Bootstrap bootstrap = parsedBootstrap.toBuilder()
                                                   .setStaticResources(staticResources)
                                                   .setDynamicResources(dynamicResources)
                                                   .build();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(listenerName)) {
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            final AtomicReference<Throwable> errorRef = new AtomicReference<>();
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                logger.info("Listener snapshot: {}, t: ", snapshot, t);
                if (snapshot != null) {
                    snapshotRef.compareAndSet(null, snapshot);
                }
                if (t != null) {
                    errorRef.compareAndSet(null, t);
                }
            });

            await().untilAsserted(() -> {
                final Throwable t = errorRef.get();
                if (t != null) {
                    throw new AssertionError("Failed to load listener snapshot via ADS", t);
                }
                final ListenerSnapshot snapshot = snapshotRef.get();
                assertThat(snapshot).isNotNull();
                assertThat(snapshot.routeSnapshot()).isNotNull();
                assertThat(snapshot.routeSnapshot().xdsResource().resource().getName())
                        .isEqualTo(routeConfigName);
            });
            logger.info("Loaded listener snapshot via ADS: {}", snapshotRef.get());
        }
    }

    @IstioPodTest
    void routeRequest() throws Exception {
        final Bootstrap parsedBootstrap = loadParsedBootstrap();

        final String listenerName = "armeria-test-ads-listener";
        final String routeConfigName = "echo-server.default.svc.cluster.local:8080";
        final Listener listener = listenerWithRdsAds(listenerName, routeConfigName);

        final Bootstrap.StaticResources staticResources =
                parsedBootstrap.getStaticResources().toBuilder()
                               .addListeners(listener)
                               .build();
        final Bootstrap bootstrap = parsedBootstrap.toBuilder()
                                                   .setStaticResources(staticResources)
                                                   .build();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(listenerName, xdsBootstrap)) {
            final WebClient client = WebClient.builder(preprocessor)
                                              .decorator(LoggingClient.newDecorator())
                                              .build();
            final AggregatedHttpResponse response = client.get("/echo").aggregate().join();
            logger.info("Response: {}", response);
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("hello");
        }
    }

    @IstioPodTest
    void listenerLoad() throws Exception {
        // Resolve the Kubernetes Service ClusterIP dynamically via in-cluster DNS.
        // Istio names outbound listeners "{clusterIP}_{port}".
        final String serviceIp = InetAddress.getByName(
                server.serviceName() + ".default.svc.cluster.local").getHostAddress();
        final String listenerName = serviceIp + '_' + server.port();
        logger.info("Istio outbound listener name resolved: {}", listenerName);

        final Bootstrap parsedBootstrap = loadParsedBootstrap();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(parsedBootstrap);
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(listenerName)) {
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            final AtomicReference<Throwable> errorRef = new AtomicReference<>();
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                logger.info("Outbound listener snapshot: {}, error: ", snapshot, t);
                if (snapshot != null) {
                    snapshotRef.compareAndSet(null, snapshot);
                }
                if (t != null) {
                    errorRef.compareAndSet(null, t);
                }
            });

            await().untilAsserted(() -> {
                final Throwable t = errorRef.get();
                if (t != null) {
                    throw new AssertionError("Failed to load outbound listener snapshot", t);
                }
                final ListenerSnapshot snapshot = snapshotRef.get();
                assertThat(snapshot).isNotNull();
                assertThat(snapshot.routeSnapshot()).isNotNull();
                assertThat(snapshot.routeSnapshot().xdsResource().resource().getName())
                        .contains(server.serviceName());
            });
            logger.info("Outbound listener snapshot loaded: {}", snapshotRef.get());
        }
    }

    @IstioPodTest
    void listenerRequest() throws Exception {
        final String serviceIp = InetAddress.getByName(
                server.serviceName() + ".default.svc.cluster.local").getHostAddress();
        final String listenerName = serviceIp + '_' + server.port();
        logger.info("Istio outbound listener name resolved: {}", listenerName);

        final Bootstrap parsedBootstrap = loadParsedBootstrap();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(parsedBootstrap);
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener(listenerName, xdsBootstrap)) {
            final WebClient client = WebClient.builder(preprocessor)
                                              .decorator(LoggingClient.newDecorator())
                                              .build();
            final AggregatedHttpResponse response = client.get("/echo").aggregate().join();
            logger.info("Response: {}", response);
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("hello");
        }
    }

    private static Listener listenerWithStaticRoute(String name, String clusterName) {
        //language=YAML
        final String yaml =
                """
                name: %s
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                    stat_prefix: %s
                    route_config:
                      name: local_route
                      virtual_hosts:
                      - name: echo_service
                        domains: ["*"]
                        routes:
                        - match:
                            prefix: "/"
                          route:
                            cluster: %s
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """.formatted(name, name, clusterName);
        return XdsResourceReader.fromYaml(yaml, Listener.class);
    }

    private static Listener listenerWithRdsAds(String name, String routeConfigName) {
        //language=YAML
        final String yaml =
                """
                name: %s
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                    stat_prefix: %s
                    rds:
                      route_config_name: %s
                      config_source:
                        ads: {}
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """.formatted(name, name, routeConfigName);
        return XdsResourceReader.fromYaml(yaml, Listener.class);
    }

    private static Bootstrap loadParsedBootstrap() throws Exception {
        final Path bootstrapPath = Paths.get("/etc/istio/proxy/envoy-rev.json");
        assertThat(bootstrapPath).exists();
        logger.info("Using Istio bootstrap file: {}", bootstrapPath);
        final String bootstrapJson = Files.readString(bootstrapPath);
        final String rewritten = XdsResourceReader.rewriteXdsGrpcBootstrap(bootstrapJson);
        return XdsResourceReader.fromJson(rewritten, Bootstrap.class);
    }
}
