/*
 * Copyright 2022 LINE Corporation
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

import static com.linecorp.armeria.it.grpc.HttpJsonTranscodingTest.findMethod;
import static com.linecorp.armeria.it.grpc.HttpJsonTranscodingTest.pathMapping;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.grpc.testing.GrpcDocServicePrefixTestServiceGrpc.GrpcDocServicePrefixTestServiceImplBase;
import com.linecorp.armeria.grpc.testing.Transcoding.GetMessageRequestV1;
import com.linecorp.armeria.grpc.testing.Transcoding.Message;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;

class GrpcDocServicePrefixTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final PrefixTextService prefixTextService = new PrefixTextService();
            final GrpcService grpcService = GrpcService.builder()
                                                       .addService(prefixTextService)
                                                       .addService("/innerPrefix", prefixTextService)
                                                       .enableHttpJsonTranscoding(true)
                                                       .build();
            sb.service(grpcService)
              .serviceUnder("/outerPrefix", grpcService)
              .route().pathPrefix("/route").build(grpcService)
              .serviceUnder("/docs/", DocService.builder().build());
        }
    };

    @Test
    void prefixTest() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        final JsonNode root = mapper.readTree(res.contentUtf8());
        System.err.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        final JsonNode methods = methods(root, "armeria.grpc.testing.GrpcDocServicePrefixTestService");
        final JsonNode httpMethods = methods(root, "armeria.grpc.testing.GrpcDocServicePrefixTestService_HTTP");

        JsonNode getMessageV1 = findMethod(methods, "GetMessageV1");
        assertThat(pathMapping(getMessageV1)).containsExactlyInAnyOrder(
                "/armeria.grpc.testing.GrpcDocServicePrefixTestService/GetMessageV1",
                "/innerPrefix/GetMessageV1",
                "/outerPrefix/armeria.grpc.testing.GrpcDocServicePrefixTestService/GetMessageV1",
                "/outerPrefix/innerPrefix/GetMessageV1",
                "/route/armeria.grpc.testing.GrpcDocServicePrefixTestService/GetMessageV1",
                "/route/innerPrefix/GetMessageV1");
        getMessageV1 = findMethod(httpMethods, "GetMessageV1");
        assertThat(pathMapping(getMessageV1)).containsExactlyInAnyOrder(
                "/v1/messages/:p0", "/outerPrefix/v1/messages/:p0");

        JsonNode getMessageV2 = findMethod(methods, "GetMessageV2");
        assertThat(pathMapping(getMessageV2)).containsExactlyInAnyOrder(
                "/armeria.grpc.testing.GrpcDocServicePrefixTestService/GetMessageV2",
                "/innerPrefix/GetMessageV2",
                "/outerPrefix/armeria.grpc.testing.GrpcDocServicePrefixTestService/GetMessageV2",
                "/outerPrefix/innerPrefix/GetMessageV2",
                "/route/armeria.grpc.testing.GrpcDocServicePrefixTestService/GetMessageV2",
                "/route/innerPrefix/GetMessageV2");
        getMessageV2 = findMethod(httpMethods, "GetMessageV2");
        assertThat(pathMapping(getMessageV2)).containsExactlyInAnyOrder(
                "/v2/messages/:message_id", "/outerPrefix/v2/messages/:message_id");
    }

    private static JsonNode methods(JsonNode root, String serviceName) {
        return StreamSupport.stream(root.get("services").spliterator(), false)
                     .filter(node -> serviceName.equals(node.get("name").asText()))
                     .findFirst().get()
                     .get("methods");
    }

    private static class PrefixTextService extends GrpcDocServicePrefixTestServiceImplBase {

        @Override
        public void getMessageV1(GetMessageRequestV1 request, StreamObserver<Message> responseObserver) {
            super.getMessageV1(request, responseObserver);
        }
    }
}
