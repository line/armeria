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

package com.linecorp.armeria.it.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.HttpJsonTranscodingTestServiceGrpc.HttpJsonTranscodingTestServiceImplBase;
import testing.grpc.Transcoding.GetMessageRequestV1;
import testing.grpc.Transcoding.Message;

class GrpcDecoratingServiceSupportHttpJsonTranscodingTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final GrpcService grpcService = GrpcService.builder().addService(
                    new HttpJsonTranscodingTestService()).enableHttpJsonTranscoding(true).build();
            sb.requestTimeoutMillis(5000);
            sb.decorator((delegate, ctx, req) -> {
                // We can aggregate request if it's not a streaming request.
                req.aggregate();
                return delegate.serve(ctx, req);
            });
            sb.decorator(LoggingService.newDecorator());
            sb.service(grpcService);
        }
    };

    private static String FIRST_TEST_RESULT = "";

    private final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private final BlockingWebClient webClient = server.webClient().blocking();

    @Test
    void shouldGetMessageV1ByWebClient() throws Exception {
        final JsonNode root = webClient.prepare()
                .get("/v1/messages/1")
                .asJson(JsonNode.class)
                .execute()
                .content();
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
        assertThat(FIRST_TEST_RESULT).isEqualTo("FirstDecorator/MethodFirstDecorator");
    }

    @Decorator(FirstDecorator.class)
    private static class HttpJsonTranscodingTestService extends HttpJsonTranscodingTestServiceImplBase {

        @Override
        @Decorator(MethodFirstDecorator.class)
        public void getMessageV1(GetMessageRequestV1 request, StreamObserver<Message> responseObserver) {
            responseObserver.onNext(Message.newBuilder().setText(request.getName()).build());
            responseObserver.onCompleted();
        }
    }

    private static class FirstDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            FIRST_TEST_RESULT += "FirstDecorator/";
            return delegate.serve(ctx, req);
        }
    }

    private static class MethodFirstDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            FIRST_TEST_RESULT += "MethodFirstDecorator";
            return delegate.serve(ctx, req);
        }
    }
}
