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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.it.istio.testing.EnabledIfDockerAvailable;
import com.linecorp.armeria.it.istio.testing.IstioClusterExtension;
import com.linecorp.armeria.it.istio.testing.IstioPodTest;
import com.linecorp.armeria.it.istio.testing.IstioServerExtension;

/**
 * Verifies that {@link IstioServerExtension} correctly deploys a server workload into the
 * K3s cluster using a {@link com.linecorp.armeria.server.ServerConfigurator} class, and that
 * the server is reachable from a test pod running inside the same cluster.
 */
@EnabledIfDockerAvailable
class EnvoyDebugTest {

    private static final Logger logger = LoggerFactory.getLogger(EnvoyDebugTest.class);

    static final int PORT = 8080;

    @RegisterExtension
    @Order(1)
    static IstioClusterExtension cluster = new IstioClusterExtension();

    @RegisterExtension
    @Order(2)
    static IstioServerExtension echo = new IstioServerExtension(
            "echo-server", PORT, EchoConfigurator.class);

    @IstioPodTest
    void serverIsReachable() {
        final WebClient client = WebClient.of("http://" + echo.serviceName() + ':' + echo.port());
        final AggregatedHttpResponse response = client.get("/echo").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("hello");
    }

    @IstioPodTest
    void envoyStatsAreReachable() {
        final WebClient envoyAdmin = WebClient.of("http://localhost:15000");
        final AggregatedHttpResponse response = envoyAdmin.get("/stats").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).contains("server.state");
    }

    @IstioPodTest
    void envoyConfigDump() {
        final WebClient envoyAdmin = WebClient.of("http://localhost:15000");
        final AggregatedHttpResponse response = envoyAdmin.get("/config_dump").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        logger.info("Envoy config dump: {}", response.contentUtf8());
    }

    @IstioPodTest
    void envoyBootstrapFile() throws Exception {
        final Path dir = Paths.get("/etc/istio/proxy");
        final List<String> filenames;
        try (Stream<Path> stream = Files.list(dir)) {
            filenames = stream.map(p -> p.getFileName().toString())
                              .collect(Collectors.toList());
        }
        logger.info("/etc/istio/proxy contents: {}", filenames);

        for (String name : filenames) {
            if (name.endsWith(".json")) {
                logger.info("Istio bootstrap file ('{}'):\n{}", name,
                            Files.readString(dir.resolve(name)));
            }
        }
    }
}
