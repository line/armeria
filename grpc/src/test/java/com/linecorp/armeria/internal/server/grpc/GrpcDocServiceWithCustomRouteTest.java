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

package com.linecorp.armeria.internal.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.assertj.core.util.Streams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class GrpcDocServiceWithCustomRouteTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final TestServiceImplBase testService = new TestServiceImplBase() {};

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService("/empty", testService, TestServiceGrpc.getEmptyCallMethod())
                                  .addService("/unary", testService, TestServiceGrpc.getUnaryCallMethod())
                                  .enableUnframedRequests(true)
                                  .build());
            sb.serviceUnder("/docs",
                            DocService.builder()
                                      .exampleRequests(TestServiceGrpc.SERVICE_NAME, "UnaryCall",
                                                       SimpleRequest.newBuilder()
                                                                    .setResponseSize(1000)
                                                                    .setFillUsername(true).build())
                                      .build()
            );
        }
    };

    @RegisterExtension
    static ServerExtension unifiedServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(testService)
                                  .enableUnframedRequests(false)
                                  .build());
            sb.serviceUnder("/internal", GrpcService.builder()
                                                    .addService("/empty", testService,
                                                                TestServiceGrpc.getEmptyCallMethod())
                                                    .addService("/unary", testService,
                                                                TestServiceGrpc.getUnaryCallMethod())
                                                    .enableUnframedRequests(true)
                                                    .build());
            sb.serviceUnder("/docs",
                            DocService.builder()
                                      .exampleRequests(TestServiceGrpc.SERVICE_NAME, "UnaryCall",
                                                       SimpleRequest.newBuilder()
                                                                    .setResponseSize(1000)
                                                                    .setFillUsername(true).build())
                                      .build()
            );
        }
    };

    @Test
    void exposeOnlyRoutingMethod() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.get("/docs/specification.json").aggregate().join();
        final JsonNode node = mapper.readValue(response.content().array(), JsonNode.class);
        final List<JsonNode> methods = ImmutableList.copyOf(node.at("/services/0/methods").elements());
        assertThat(methods).hasSize(2);
        assertThat(methods).anyMatch(method -> "EmptyCall".equals(method.get("name").textValue()));
        assertThat(methods).anyMatch(method -> "UnaryCall".equals(method.get("name").textValue()));
    }

    @Test
    void mixUnframedWithFramed() throws Exception {
        final WebClient client = WebClient.of(unifiedServer.httpUri());
        final AggregatedHttpResponse response = client.get("/docs/specification.json").aggregate().join();

        final JsonNode node = mapper.readValue(response.content().array(), JsonNode.class);
        final List<JsonNode> methods = ImmutableList.copyOf(node.at("/services/0/methods").elements());
        assertThat(methods).hasSize(testService.bindService().getMethods().size());

        final MediaType protobuf = MediaType.PROTOBUF.withParameter("protocol", "gRPC");
        final MediaType json = MediaType.JSON_UTF_8.withParameter("protocol", "gRPC");
        for (JsonNode method : methods) {
            final JsonNode endpoints = method.get("endpoints");
            final String methodName = method.get("name").textValue();
            if ("EmptyCall".equals(methodName) || "UnaryCall".equals(methodName)) {
                assertThat(validateEndpointMimeType(endpoints, protobuf)).isTrue();
                assertThat(validateEndpointMimeType(endpoints, json)).isTrue();
            } else {
                assertThat(validateEndpointMimeType(endpoints, protobuf)).isFalse();
                assertThat(validateEndpointMimeType(endpoints, json)).isFalse();
            }
        }
    }

    private static boolean validateEndpointMimeType(JsonNode endpoint, MediaType mediaType) {
        return Streams.stream(endpoint.elements())
                       .flatMap(n -> Streams.stream(n.get("availableMimeTypes").elements()))
                       .filter(n -> n.textValue().equals(mediaType.toString()))
                       .count() == 1;
    }
}
