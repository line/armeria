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

package com.linecorp.armeria.it.grpc;

import static com.linecorp.armeria.it.grpc.HttpJsonTranscodingTest.findMethod;
import static com.linecorp.armeria.it.grpc.HttpJsonTranscodingTest.pathMapping;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.internal.testing.TestUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.grpc.DelegatingHttpJsonTranscodingService;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.grpc.HttpJsonTranscodingTestServiceGrpc;

class DelegatingHttpJsonTranscodingDocServiceTest {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            if (TestUtil.isDocServiceDemoMode()) {
                sb.http(8080);
            }
            final GrpcService grpcService =
                    GrpcService.builder()
                               .addService(new HttpJsonTranscodingTestService())
                               .build();
            final DelegatingHttpJsonTranscodingService transcoder =
                    DelegatingHttpJsonTranscodingService.builder(grpcService)
                                                        .serviceDescriptors(
                                                                HttpJsonTranscodingTestServiceGrpc
                                                                        .getServiceDescriptor())
                                                        .transcodedGrpcSerializationFormat(
                                                                GrpcSerializationFormats.PROTO)
                                                        .build();
            final DelegatingHttpJsonTranscodingService prefixedTranscoder =
                    DelegatingHttpJsonTranscodingService.builder(grpcService)
                                                        .serviceDescriptors(
                                                                HttpJsonTranscodingTestServiceGrpc
                                                                        .getServiceDescriptor())
                                                        .transcodedGrpcSerializationFormat(
                                                                GrpcSerializationFormats.PROTO)
                                                        .build();
            sb.service(transcoder)
              .serviceUnder("/prefix", prefixedTranscoder)
              .serviceUnder("/docs/", DocService.builder().build());
        }
    };

    @Test
    void shouldExposeHttpJsonTranscodingEndpointsInDocService() throws Exception {
        if (TestUtil.isDocServiceDemoMode()) {
            Thread.sleep(Long.MAX_VALUE);
        }
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        final JsonNode root = mapper.readTree(res.contentUtf8());
        final String httpServiceName = HttpJsonTranscodingTestServiceGrpc.SERVICE_NAME + "_HTTP";
        final JsonNode httpMethods = methods(root, httpServiceName);

        final JsonNode getMessageV1 = findMethod(httpMethods, "GetMessageV1");
        final List<String> paths = pathMapping(getMessageV1);
        assertThat(paths).contains("/v1/messages/:p0");
        assertThat(paths).contains("/prefix/v1/messages/:p0");

        assertThat(serviceNames(root)).doesNotContain(HttpJsonTranscodingTestServiceGrpc.SERVICE_NAME);
    }

    private static JsonNode methods(JsonNode root, String serviceName) {
        return Streams.stream(root.get("services"))
                      .filter(node -> serviceName.equals(node.get("name").asText()))
                      .findFirst().get()
                      .get("methods");
    }

    private static List<String> serviceNames(JsonNode root) {
        return Streams.stream(root.get("services"))
                      .map(node -> node.get("name").asText())
                      .collect(ImmutableList.toImmutableList());
    }
}
