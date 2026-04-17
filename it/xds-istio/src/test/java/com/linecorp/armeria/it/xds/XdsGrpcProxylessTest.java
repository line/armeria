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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jayway.jsonpath.JsonPath;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.it.istio.testing.EnabledIfDockerAvailable;
import com.linecorp.armeria.it.istio.testing.IstioClusterExtension;
import com.linecorp.armeria.it.istio.testing.IstioPodTest;
import com.linecorp.armeria.it.istio.testing.IstioServerExtension;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

/**
 * Tests xDS with Istio's gRPC proxyless mode ({@code GENERATOR: grpc}).
 * This is not using full gRPC proxyless mode, but just the gRPC generator for xDS configs.
 *
 * <p>In this mode, Istiod generates xDS configs designed for non-Envoy gRPC clients:
 * listener names use FQDNs (e.g. {@code echo-server.default.svc.cluster.local:8080})
 * instead of IP-based names (e.g. {@code 10.43.139.201_8080}).
 */
@EnabledIfDockerAvailable
class XdsGrpcProxylessTest {

    private static final Logger logger = LoggerFactory.getLogger(XdsGrpcProxylessTest.class);

    @RegisterExtension
    @Order(1)
    static IstioClusterExtension cluster = new IstioClusterExtension();

    @RegisterExtension
    @Order(2)
    static IstioServerExtension server =
            new IstioServerExtension("echo-server", 8080, EchoConfigurator.class);

    @IstioPodTest
    void listenerLoad() throws Exception {
        final String listenerName =
                server.serviceName() + ".default.svc.cluster.local:" + server.port();
        logger.info("gRPC proxyless listener name: {}", listenerName);

        final Bootstrap parsedBootstrap = loadParsedBootstrap();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(parsedBootstrap);
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(listenerName)) {
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            final AtomicReference<Throwable> errorRef = new AtomicReference<>();
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                logger.info("gRPC proxyless listener snapshot: {}, error: ", snapshot, t);
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
                    throw new AssertionError("Failed to load gRPC proxyless listener snapshot", t);
                }
                final ListenerSnapshot snapshot = snapshotRef.get();
                assertThat(snapshot).isNotNull();
                assertThat(snapshot.routeSnapshot()).isNotNull();
            });
            logger.info("gRPC proxyless listener snapshot loaded: {}", snapshotRef.get());
            logger.info("gRPC proxyless listener snapshot loaded: {}", snapshotRef.get().toDebugString());
        }
    }

    @IstioPodTest
    void listenerRequest() throws Exception {
        final String listenerName =
                server.serviceName() + ".default.svc.cluster.local:" + server.port();
        logger.info("gRPC proxyless listener name: {}", listenerName);

        final Bootstrap parsedBootstrap = loadParsedBootstrap();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(parsedBootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(listenerName, xdsBootstrap)) {
            final WebClient client = WebClient.builder(preprocessor)
                                              .decorator(LoggingClient.newDecorator())
                                              .build();
            final AggregatedHttpResponse response = client.execute(
                    RequestHeaders.builder(HttpMethod.GET, "/echo")
                            .authority("echo-server")
                            .build()).aggregate().join();
            logger.info("Response: {}", response);
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("hello");
        }
    }

    private static Bootstrap loadParsedBootstrap() throws Exception {
        final Path bootstrapPath = Paths.get("/etc/istio/proxy/envoy-rev.json");
        assertThat(bootstrapPath).exists();
        logger.info("Using Istio bootstrap file: {}", bootstrapPath);
        final String bootstrapJson = Files.readString(bootstrapPath);
        final String rewritten = XdsResourceReader.rewriteXdsGrpcBootstrap(bootstrapJson);
        // Set GENERATOR=grpc in node metadata to enable gRPC proxyless mode.
        final String withGenerator = JsonPath.parse(rewritten)
                                             .put("$.node.metadata", "GENERATOR", "grpc")
                                             .jsonString();
        return XdsResourceReader.fromJson(withGenerator, Bootstrap.class);
    }
}
