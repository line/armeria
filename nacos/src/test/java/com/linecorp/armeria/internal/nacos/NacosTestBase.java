/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.internal.nacos;

import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.internal.testing.FlakyTest;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A helper class for testing with Nacos.
 */
@FlakyTest
@Testcontainers(disabledWithoutDocker = true)
public abstract class NacosTestBase {
    protected static final String serviceName = "testService";
    protected static final String NACOS_AUTH_TOKEN = "armeriaarmeriaarmeriaarmeriaarmeriaarmeriaarmeriaarmeria";
    protected static final String NACOS_AUTH_SECRET = "nacos";

    protected static List<Endpoint> newSampleEndpoints() {
        final int[] ports = unusedPorts(3);
        return ImmutableList.of(Endpoint.of("host.docker.internal", ports[0]).withWeight(2),
                                Endpoint.of("host.docker.internal", ports[1]).withWeight(4),
                                Endpoint.of("host.docker.internal", ports[2]).withWeight(2));
    }

    @Container
    static final GenericContainer nacosContainer =
            new GenericContainer(DockerImageName.parse("nacos/nacos-server:v2.3.0-slim"))
                    .withExposedPorts(8848)
                    .withEnv("MODE", "standalone")
                    .withEnv("NACOS_AUTH_ENABLE", "true")
                    .withEnv("NACOS_AUTH_TOKEN", NACOS_AUTH_TOKEN)
                    .withEnv("NACOS_AUTH_IDENTITY_KEY", NACOS_AUTH_SECRET)
                    .withEnv("NACOS_AUTH_IDENTITY_VALUE", NACOS_AUTH_SECRET);

    @Nullable
    private static URI nacosUri;

    protected NacosTestBase() {}

    @Nullable
    private static NacosClient nacosClient;

    @BeforeAll
    static void start() throws Throwable {
        // Initialize Nacos Client
        nacosUri = URI.create(
                "http://" + nacosContainer.getHost() + ':' + nacosContainer.getMappedPort(8848));

        nacosClient = NacosClient.builder(nacosUri)
                .authorization(NACOS_AUTH_SECRET, NACOS_AUTH_SECRET)
                .build();
    }

    protected static NacosClient client() {
        if (nacosClient == null) {
            throw new IllegalStateException("nacos client has not initialized");
        }
        return nacosClient;
    }

    protected static URI nacosUri() {
        checkState(nacosUri != null, "nacosUri has not initialized.");
        return nacosUri;
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

    public static class EchoService extends AbstractHttpService {
        private volatile HttpStatus responseStatus = HttpStatus.OK;

        @Override
        protected final HttpResponse doHead(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(req.aggregate()
                                      .thenApply(aReq -> HttpResponse.of(HttpStatus.OK))
                                      .exceptionally(CompletionActions::log));
        }

        @Override
        protected final HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(req.aggregate()
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
