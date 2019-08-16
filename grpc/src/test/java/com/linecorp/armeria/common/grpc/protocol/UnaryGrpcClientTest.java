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

package com.linecorp.armeria.common.grpc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

public class UnaryGrpcClientTest {

    private static class TestService extends TestServiceImplBase {

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            final SimpleResponse response = SimpleResponse.newBuilder()
                                                          .setPayload(request.getPayload())
                                                          .build();
            if (request.getPayload().getBody().toStringUtf8().equals("ice cream")) {
                responseObserver.onNext(response);
                responseObserver.onError(new StatusException(Status.UNAVAILABLE));
            } else {
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            }
        }
    }

    private static Server server;

    @BeforeClass
    public static void setupServer() throws Exception {
        server = NettyServerBuilder.forPort(0)
                                   .addService(new TestService())
                                   .build()
                                   .start();
    }

    @AfterClass
    public static void stopServer() {
        server.shutdownNow();
    }

    private UnaryGrpcClient client;

    @Before
    public void setUp() {
        client = new UnaryGrpcClient(HttpClient.of("http://127.0.0.1:" + server.getPort()));
    }

    @Test
    public void normal() throws Exception {
        final SimpleRequest request = SimpleRequest.newBuilder()
                                                   .setPayload(Payload.newBuilder()
                                                                .setBody(ByteString.copyFromUtf8("hello"))
                                                                .build())
                                                   .build();

        final byte[] responseBytes =
                client.execute("/armeria.grpc.testing.TestService/UnaryCall", request.toByteArray()).join();
        final SimpleResponse response = SimpleResponse.parseFrom(responseBytes);
        assertThat(response.getPayload().getBody().toStringUtf8()).isEqualTo("hello");
    }

    /** This shows we can handle status that happens in trailers. */
    @Test
    public void lateStatusException() {
        final SimpleRequest request = SimpleRequest.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFromUtf8("ice cream"))
                .build())
            .build();

        assertThatThrownBy(
            () -> client.execute("/armeria.grpc.testing.TestService/UnaryCall",
                request.toByteArray()).join())
            .isInstanceOf(CompletionException.class)
            .hasCauseInstanceOf(ArmeriaStatusException.class);
    }

    @Test
    public void invalidPayload() {
        assertThatThrownBy(
                () -> client.execute("/armeria.grpc.testing.TestService/UnaryCall",
                                     "foobarbreak".getBytes(StandardCharsets.UTF_8)).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ArmeriaStatusException.class);
    }
}
