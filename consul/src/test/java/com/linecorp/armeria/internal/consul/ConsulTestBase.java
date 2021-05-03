/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.internal.consul;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.pszymczyk.consul.ConsulProcess;
import com.pszymczyk.consul.ConsulStarterBuilder;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

public abstract class ConsulTestBase {

    private static final Logger logger = LoggerFactory.getLogger(ConsulTestBase.class);

    private static final String ENV_CONSUL_VERSION = "CONSUL_VERSION";
    private static final String ENV_CONSUL_BINARY_DOWNLOAD_DIR = "CONSUL_BINARY_DOWNLOAD_DIR";
    private static final String FALLBACK_CONSUL_VERSION = "1.9.3";

    protected static final String CONSUL_TOKEN = UUID.randomUUID().toString();
    protected static final String serviceName = "testService";

    protected static List<Endpoint> newSampleEndpoints() {
        final int[] ports = unusedPorts(3);
        return ImmutableList.of(Endpoint.of("localhost", ports[0]).withWeight(2),
                                Endpoint.of("127.0.0.1", ports[1]).withWeight(4),
                                Endpoint.of("127.0.0.1", ports[2]).withWeight(2));
    }

    protected ConsulTestBase() {}

    @Nullable
    private static ConsulProcess consul;

    @Nullable
    private static ConsulClient consulClient;

    @BeforeAll
    static void start() throws Throwable {
        // Initialize Consul embedded server for testing
        // This EmbeddedConsul tested with Consul version above 1.4.0
        final ConsulStarterBuilder builder =
                ConsulStarterBuilder.consulStarter()
                                    .withWaitTimeout(120)
                                    .withCustomConfig(aclConfiguration(CONSUL_TOKEN))
                                    .withToken(CONSUL_TOKEN);

        final String version = System.getenv(ENV_CONSUL_VERSION);
        if (!Strings.isNullOrEmpty(version)) {
            builder.withConsulVersion(version);
            logger.info("{}={}", ENV_CONSUL_VERSION, version);
        } else {
            builder.withConsulVersion(FALLBACK_CONSUL_VERSION);
            logger.warn("{}={} (fallback)", ENV_CONSUL_VERSION, FALLBACK_CONSUL_VERSION);
        }

        final String downloadDir = System.getenv(ENV_CONSUL_BINARY_DOWNLOAD_DIR);
        if (!Strings.isNullOrEmpty(downloadDir)) {
            builder.withConsulBinaryDownloadDirectory(Paths.get(downloadDir));
            logger.info("{}={}", ENV_CONSUL_BINARY_DOWNLOAD_DIR, downloadDir);
        } else {
            logger.warn("{}=<unspecified>", ENV_CONSUL_BINARY_DOWNLOAD_DIR);
        }

        // A workaround for 'Cannot run program "**/embedded_consul/consul" error=26, Text file busy'
        await().timeout(Duration.ofSeconds(30)).pollInSameThread().pollInterval(Duration.ofSeconds(2))
               .untilAsserted(() -> {
                   assertThatCode(() -> {
                       consul = builder.build().start();
                   }).doesNotThrowAnyException();
               });

        // Initialize Consul client
        consulClient = ConsulClient.builder(URI.create("http://127.0.0.1:" + consul.getHttpPort()))
                                   .consulToken(CONSUL_TOKEN)
                                   .build();
    }

    @AfterAll
    static void stop() throws Throwable {
        if (consul != null) {
            consul.close();
            consul = null;
        }
        if (consulClient != null) {
            consulClient = null;
        }
    }

    protected static ConsulProcess consul() {
        if (consul == null) {
            throw new IllegalStateException("embedded consul has not initialized");
        }
        return consul;
    }

    protected static ConsulClient client() {
        if (consulClient == null) {
            throw new IllegalStateException("consul client has not initialized");
        }
        return consulClient;
    }

    protected static int[] unusedPorts(int numPorts) {
        final int[] ports = new int[numPorts];
        final Random random = ThreadLocalRandom.current();
        for (int i = 0; i < numPorts; i++) {
            for (;;) {
                final int candidatePort = random.nextInt(64512) + 1024;
                try (ServerSocket ss = new ServerSocket()) {
                    ss.bind(new InetSocketAddress("127.0.0.1", candidatePort));
                    ports[i] = candidatePort;
                    break;
                } catch (IOException e) {
                    // Port in use or unable to bind.
                    continue;
                }
            }
        }

        return ports;
    }

    private static String aclConfiguration(String token) {
        return
                new StringBuilder()
                        .append('{')
                        .append("\"acl\": {")
                        .append("\"enabled\": true, ")
                        .append("\"default_policy\": \"deny\", ")
                        .append("\"down_policy\": \"deny\", ")
                        .append("\"tokens\": {")
                        .append("    \"agent\": \"").append(token).append("\", ")
                        .append("    \"master\": \"").append(token).append("\", ")
                        .append("    }")
                        .append('}')
                        .toString();
    }

    public static class EchoService extends AbstractHttpService {
        private volatile HttpStatus responseStatus = HttpStatus.OK;

        @Override
        protected final HttpResponse doHead(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.from(req.aggregate()
                                        .thenApply(aReq -> HttpResponse.of(HttpStatus.OK))
                                        .exceptionally(CompletionActions::log));
        }

        @Override
        protected final HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.from(req.aggregate()
                                        .thenApply(this::echo)
                                        .exceptionally(CompletionActions::log));
        }

        protected HttpResponse echo(AggregatedHttpRequest aReq) {
            final HttpStatus httpStatus = HttpStatus.valueOf(aReq.contentUtf8());
            if (httpStatus != HttpStatus.UNKNOWN) {
                responseStatus = httpStatus;
            }
            return HttpResponse.of(ResponseHeaders.of(responseStatus), aReq.content());
        }
    }
}
