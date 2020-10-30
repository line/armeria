/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.client.grpc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

class UnaryGrpcClientTest {

    private static class TestService extends TestServiceImplBase {

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            final SimpleResponse response = SimpleResponse.newBuilder()
                                                          .setPayload(request.getPayload())
                                                          .build();
            final String payload = request.getPayload().getBody().toStringUtf8();
            if ("peanuts".equals(payload)) {
                responseObserver.onError(
                        new StatusException(Status.UNAVAILABLE.withDescription("we don't sell peanuts"))
                );
            } else if ("ice cream".equals(payload)) {
                responseObserver.onNext(response); // Note: we error after the response, so trailers
                responseObserver.onError(
                        new StatusException(Status.UNAVAILABLE.withDescription("no more ice cream"))
                );
            } else {
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            }
        }
    }

    private static Server server;

    @BeforeAll
    static void setupServer() throws Exception {
        server = NettyServerBuilder.forPort(0)
                                   .addService(new TestService())
                                   .build()
                                   .start();
    }

    @AfterAll
    static void stopServer() {
        server.shutdownNow();
    }

    private UnaryGrpcClient client;

    @BeforeEach
    void setUp() {
        client = new UnaryGrpcClient(WebClient.of("http://127.0.0.1:" + server.getPort()));
    }

    @Test
    void normal() throws Exception {
        final SimpleRequest request = SimpleRequest.newBuilder()
                                                   .setPayload(Payload.newBuilder()
                                                                      .setBody(ByteString.copyFromUtf8("hello"))
                                                                      .build())
                                                   .build();

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final byte[] responseBytes =
                    client.execute("/armeria.grpc.testing.TestService/UnaryCall", request.toByteArray()).join();
            final ClientRequestContext ctx = captor.get();
            final HttpHeaders trailers = ctx.log().whenComplete().join().responseTrailers();
            final int status = trailers.getInt(GrpcHeaderNames.GRPC_STATUS);
            assertThat(status).isZero();
            final SimpleResponse response = SimpleResponse.parseFrom(responseBytes);
            assertThat(response.getPayload().getBody().toStringUtf8()).isEqualTo("hello");
        }
    }

    /** This shows we can handle status that happens in headers. */
    @Test
    void statusException() {
        final SimpleRequest request = SimpleRequest.newBuilder()
                                                   .setPayload(Payload.newBuilder()
                                                                      .setBody(ByteString
                                                                                       .copyFromUtf8("peanuts"))
                                                                      .build())
                                                   .build();

        assertThatThrownBy(
                () -> client.execute("/armeria.grpc.testing.TestService/UnaryCall",
                                     request.toByteArray()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ArmeriaStatusException.class)
                .hasMessageContaining("we don't sell peanuts");
    }

    /** This shows we can handle status that happens in trailers. */
    @Test
    void lateStatusException() {
        final SimpleRequest request = SimpleRequest.newBuilder()
                                                   .setPayload(Payload.newBuilder()
                                                                      .setBody(ByteString.copyFromUtf8(
                                                                              "ice cream"))
                                                                      .build())
                                                   .build();

        assertThatThrownBy(
                () -> client.execute("/armeria.grpc.testing.TestService/UnaryCall",
                                     request.toByteArray()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ArmeriaStatusException.class)
                .hasMessageContaining("no more ice cream");
    }

    @Test
    void invalidPayload() {
        assertThatThrownBy(
                () -> client.execute("/armeria.grpc.testing.TestService/UnaryCall",
                                     "foobarbreak".getBytes(StandardCharsets.UTF_8)).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ArmeriaStatusException.class);
    }
}
