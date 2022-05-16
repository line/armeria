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

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class UnframedGrpcErrorHandlerTest {
    @RegisterExtension
    static ServerExtension nonVerboseServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, false, UnframedGrpcErrorHandler.of());
        }
    };

    @RegisterExtension
    static ServerExtension verbosePlainTextResServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, true, UnframedGrpcErrorHandler.ofPlainText());
        }
    };

    @RegisterExtension
    static ServerExtension verboseJsonResServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, true, UnframedGrpcErrorHandler.ofJson());
        }
    };

    private static class TestService extends TestServiceImplBase {

        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            throw Status.UNKNOWN.withDescription("grpc error message").asRuntimeException();
        }
    }

    private static final TestService testService = new TestService();

    private static void configureServer(ServerBuilder sb, boolean verboseResponses,
                                        UnframedGrpcErrorHandler errorHandler) {
        sb.verboseResponses(verboseResponses);
        sb.service(GrpcService.builder()
                              .addService(testService)
                              .enableUnframedRequests(true)
                              .unframedGrpcErrorHandler(errorHandler)
                              .build());
    }

    @Test
    void withoutStackTrace() {
        final BlockingWebClient client = nonVerboseServer.webClient().blocking();
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(MediaType.PROTOBUF, Empty.getDefaultInstance().toByteArray())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        final String content = response.contentUtf8();
        assertThat(content).isEqualTo("grpc-code: UNKNOWN, grpc error message");
        assertThat(response.trailers()).isEmpty();
    }

    @Test
    void plainTextWithStackTrace() {
        final BlockingWebClient client = verbosePlainTextResServer.webClient().blocking();
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(MediaType.PROTOBUF, Empty.getDefaultInstance().toByteArray())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        final String content = response.contentUtf8();
        assertThat(content).startsWith("grpc-code: UNKNOWN, grpc error message" +
                                       "\nstack-trace:\nio.grpc.StatusException");
        assertThat(response.trailers()).isEmpty();
    }

    @Test
    void jsonWithStackTrace() {
        final BlockingWebClient client = verboseJsonResServer.webClient().blocking();
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(MediaType.PROTOBUF, Empty.getDefaultInstance().toByteArray())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        final String content = response.contentUtf8();
        assertThat(content).startsWith("{\"grpc-code\":\"UNKNOWN\",\"message\":\"grpc error message\"," +
                                       "\"stack-trace\":\"io.grpc.StatusException");
        assertThat(response.trailers()).isEmpty();
    }
}
