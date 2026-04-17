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
package com.linecorp.armeria.it.xds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.it.istio.testing.EnabledIfDockerAvailable;
import com.linecorp.armeria.it.istio.testing.GrpcProxylessPodCustomizer;
import com.linecorp.armeria.it.istio.testing.IstioClusterExtension;
import com.linecorp.armeria.it.istio.testing.IstioPodTest;
import com.linecorp.armeria.it.istio.testing.IstioServerExtension;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

/**
 * Tests xDS with Istio's native gRPC proxyless mode. Unlike {@link XdsGrpcProxylessTest} which
 * reads the Envoy bootstrap and mutates it, this test uses the {@code grpc-agent} template
 * ({@link GrpcProxylessPodCustomizer}) which produces a gRPC-style bootstrap. The node identity
 * is extracted from that bootstrap and used to construct an Envoy-style bootstrap that Armeria's
 * {@link XdsBootstrap} can consume.
 */
@EnabledIfDockerAvailable
class XdsNativeGrpcProxylessTest {

    private static final Logger logger = LoggerFactory.getLogger(XdsNativeGrpcProxylessTest.class);

    @RegisterExtension
    @Order(1)
    static IstioClusterExtension cluster = new IstioClusterExtension();

    @RegisterExtension
    @Order(2)
    static IstioServerExtension server =
            new IstioServerExtension("echo-server", 8080, EchoConfigurator.class);

    @IstioPodTest(podCustomizer = GrpcProxylessPodCustomizer.class)
    void bootstrapFile() throws Exception {
        final Path dir = Paths.get("/etc/istio/proxy");
        final List<String> filenames;
        try (Stream<Path> stream = Files.list(dir)) {
            filenames = stream.map(p -> p.getFileName().toString())
                              .collect(Collectors.toList());
        }
        logger.info("/etc/istio/proxy contents: {}", filenames);
        assertThat(filenames).anyMatch(name -> name.endsWith(".json"));

        for (String name : filenames) {
            if (name.endsWith(".json")) {
                logger.info("gRPC proxyless bootstrap file ('{}'):\n{}", name,
                            Files.readString(dir.resolve(name)));
            }
        }
    }

    @IstioPodTest(podCustomizer = GrpcProxylessPodCustomizer.class)
    void listenerLoad() throws Exception {
        final String listenerName =
                server.serviceName() + ".default.svc.cluster.local:" + server.port();
        logger.info("Native gRPC proxyless listener name: {}", listenerName);

        final Bootstrap parsedBootstrap = loadParsedBootstrap();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(parsedBootstrap);
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(listenerName)) {
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            final AtomicReference<Throwable> errorRef = new AtomicReference<>();
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                logger.info("Native gRPC proxyless listener snapshot: {}, error: ", snapshot, t);
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
                    throw new AssertionError(
                            "Failed to load native gRPC proxyless listener snapshot", t);
                }
                final ListenerSnapshot snapshot = snapshotRef.get();
                assertThat(snapshot).isNotNull();
                assertThat(snapshot.routeSnapshot()).isNotNull();
            });
            logger.info("Native gRPC proxyless listener snapshot loaded: {}", snapshotRef.get());
            logger.info("Native gRPC proxyless listener snapshot loaded: {}",
                        snapshotRef.get().toDebugString());
        }
    }

    @IstioPodTest(podCustomizer = GrpcProxylessPodCustomizer.class)
    void listenerRequest() throws Exception {
        final String listenerName =
                server.serviceName() + ".default.svc.cluster.local:" + server.port();
        logger.info("Native gRPC proxyless listener name: {}", listenerName);

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

    /**
     * Reads the gRPC-style bootstrap ({@code grpc-bootstrap.json}) generated by the
     * {@code grpc-agent} sidecar, extracts the node identity and server URI, and
     * constructs an Envoy-style {@link Bootstrap} that Armeria's {@link XdsBootstrap}
     * can consume.
     */
    private static Bootstrap loadParsedBootstrap() throws Exception {
        final Path bootstrapPath = Paths.get("/etc/istio/proxy/grpc-bootstrap.json");
        assertThat(bootstrapPath).exists();
        final String bootstrapJson = Files.readString(bootstrapPath);
        logger.info("gRPC bootstrap:\n{}", bootstrapJson);

        final DocumentContext ctx = JsonPath.parse(bootstrapJson);
        final String nodeJson = Configuration.defaultConfiguration()
                                             .jsonProvider()
                                             .toJson(ctx.read("$.node"));
        // server_uri is e.g. "unix:///etc/istio/proxy/XDS"
        final String serverUri = ctx.read("$.xds_servers[0].server_uri");
        final String pipePath = serverUri.replaceFirst("^unix://", "");

        //language=YAML
        final String yaml =
                """
                node: %s
                dynamic_resources:
                  ads_config:
                    api_type: GRPC
                    set_node_on_first_message_only: true
                    grpc_services:
                    - envoy_grpc:
                        cluster_name: xds-grpc
                  lds_config:
                    ads: {}
                  cds_config:
                    ads: {}
                static_resources:
                  clusters:
                  - name: xds-grpc
                    type: STATIC
                    load_assignment:
                      cluster_name: xds-grpc
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              pipe:
                                path: %s
                """.formatted(nodeJson, pipePath);

        return XdsResourceReader.fromYaml(yaml, Bootstrap.class);
    }
}
