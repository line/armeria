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

import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
 * A helper class for testing with Consul.
 */
@FlakyTest
@Testcontainers(disabledWithoutDocker = true)
public abstract class ConsulTestBase {

    private static final Logger logger = LoggerFactory.getLogger(ConsulTestBase.class);

    protected static final String CONSUL_TOKEN = UUID.randomUUID().toString();
    protected static final String serviceName = "testService";

    protected static List<Endpoint> newSampleEndpoints() {
        final int[] ports = unusedPorts(3);
        return ImmutableList.of(Endpoint.of("host.docker.internal", ports[0]).withWeight(2),
                                Endpoint.of("host.docker.internal", ports[1]).withWeight(4),
                                Endpoint.of("host.docker.internal", ports[2]).withWeight(2));
    }

    @Container
    static final ConsulContainer consulContainer = new ConsulContainer("hashicorp/consul:1.15")
            .withLogConsumer(frame -> logger.debug(frame.getUtf8StringWithoutLineEnding()))
            .withExtraHost("host.docker.internal", "host-gateway")
            .withEnv("CONSUL_LOCAL_CONFIG", aclConfiguration(CONSUL_TOKEN));

    @Nullable
    private static URI consulUri;

    protected ConsulTestBase() {}

    @Nullable
    private static ConsulClient consulClient;

    @BeforeAll
    static void start() throws Throwable {
        // Initialize Consul client
        consulUri = URI.create(
                "http://" + consulContainer.getHost() + ':' + consulContainer.getMappedPort(8500));

        consulClient = ConsulClient.builder(consulUri)
                                   .consulToken(CONSUL_TOKEN)
                                   .build();
    }

    protected static ConsulClient client() {
        if (consulClient == null) {
            throw new IllegalStateException("consul client has not initialized");
        }
        return consulClient;
    }

    protected static URI consulUri() {
        checkState(consulUri != null, "consulUri has not initialized.");
        return consulUri;
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
        return new StringBuilder()
                .append('{')
                .append("  \"acl\": {")
                .append("    \"enabled\": true, ")
                .append("    \"default_policy\": \"deny\", ")
                .append("    \"down_policy\": \"deny\", ")
                .append("    \"tokens\": {")
                .append("      \"agent\": \"").append(token).append("\", ")
                .append("      \"initial_management\": \"").append(token).append('"')
                .append("    }")
                .append("  }")
                .append('}')
                .toString();
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
