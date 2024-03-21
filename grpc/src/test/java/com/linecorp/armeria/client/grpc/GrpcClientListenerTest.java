/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientCall.Listener;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.stub.StreamObserver;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceStub;

class GrpcClientListenerTest {

    private static final Key<String> key = InternalMetadata.keyOf("hello", Metadata.ASCII_STRING_MARSHALLER);

    private static final ServerInterceptor interceptor = new ServerInterceptor() {
        @Override
        public <I, O> ServerCall.Listener<I> interceptCall(
                ServerCall<I, O> call, Metadata headers,
                ServerCallHandler<I, O> next) {
            return next.startCall(new SimpleForwardingServerCall<I, O>(call) {
                                      @Override
                                      public void sendHeaders(Metadata headers) {
                                          headers.put(key, "world");
                                          super.sendHeaders(headers);
                                      }
                                  }, headers);
        }
    };

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            sb.decorator(LoggingService.newDecorator());
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl(executor) {
                                      @Override
                                      public void emptyCall(Empty empty,
                                                            StreamObserver<Empty> responseObserver) {
                                          responseObserver.onCompleted();
                                      }
                                  })
                                  .intercept(interceptor)
                                  .build());
            sb.service("/fail/armeria.grpc.testing.TestService/UnaryCall",
                       (ctx, req) -> HttpResponse.of(HttpStatus.NO_CONTENT));
            sb.service("/trailers-only/armeria.grpc.testing.TestService/UnaryCall", (ctx, req) -> {
                return HttpResponse.builder()
                                   .header(GrpcHeaderNames.GRPC_STATUS.toString(),
                                           Status.UNKNOWN.getCode().value())
                                   .ok()
                                   .build();
            });
        }
    };

    static class TestClientCallListener extends Listener<SimpleResponse> {
        @Nullable
        Metadata headers;
        @Nullable
        Metadata trailers;
        @Nullable
        Status status;
        @Nullable
        SimpleResponse response;

        @Override
        public void onHeaders(Metadata headers) {
            this.headers = headers;
        }

        @Override
        public void onMessage(SimpleResponse response) {
            this.response = response;
        }

        @Override
        public void onClose(Status status, Metadata trailers) {
            this.status = status;
            this.trailers = trailers;
        }
    }

    @Test
    void trailersOnly() {
        final TestServiceStub client = GrpcClients.builder(server.httpUri().resolve("/trailers-only/"))
                                                  .build(TestServiceStub.class);
        final ClientCall<SimpleRequest, SimpleResponse> unaryCall =
                client.getChannel().newCall(TestServiceGrpc.getUnaryCallMethod(), CallOptions.DEFAULT);
        final TestClientCallListener listener = new TestClientCallListener();

        unaryCall.start(listener, new Metadata());

        unaryCall.sendMessage(SimpleRequest.getDefaultInstance());
        unaryCall.halfClose();
        // Send 1 request and expect to receive a message and trailers
        unaryCall.request(1);

        // both headers/response is not set
        await().until(() -> listener.trailers, Matchers.notNullValue());
        assertThat(listener.trailers.keys()).contains("date", "server", "content-length");
        assertThat(listener.headers).isNull();
        assertThat(listener.response).isNull();
        assertThat(listener.status.getCode()).isEqualTo(Code.UNKNOWN);
        assertThat(listener.status.getDescription()).isNull();
    }

    @Test
    void failedProtocolResponse() {
        final TestServiceStub client = GrpcClients.builder(server.httpUri().resolve("/fail/"))
                                                  .build(TestServiceStub.class);
        final ClientCall<SimpleRequest, SimpleResponse> unaryCall =
                client.getChannel().newCall(TestServiceGrpc.getUnaryCallMethod(), CallOptions.DEFAULT);
        final TestClientCallListener listener = new TestClientCallListener();

        unaryCall.start(listener, new Metadata());

        unaryCall.sendMessage(SimpleRequest.getDefaultInstance());
        unaryCall.halfClose();
        // Send 1 request and expect to receive a message and trailers
        unaryCall.request(1);

        // both headers/response is not set
        await().until(() -> listener.trailers, Matchers.notNullValue());
        assertThat(listener.trailers.keys()).isEmpty();
        assertThat(listener.headers).isNull();
        assertThat(listener.response).isNull();
        assertThat(listener.status.getCode()).isEqualTo(Code.UNKNOWN);
        assertThat(listener.status.getDescription()).isEqualTo("HTTP status code 204");
    }

    @Test
    void requestOneAndReceiveMessageAndTrailers() {
        final TestServiceStub client = GrpcClients.builder(server.httpUri())
                                                  .build(TestServiceStub.class);
        final ClientCall<SimpleRequest, SimpleResponse> unaryCall =
                client.getChannel().newCall(TestServiceGrpc.getUnaryCallMethod(), CallOptions.DEFAULT);
        final TestClientCallListener listener = new TestClientCallListener();

        unaryCall.start(listener, new Metadata());

        unaryCall.sendMessage(SimpleRequest.getDefaultInstance());
        unaryCall.halfClose();
        // Send 1 request and expect to receive a message and trailers
        unaryCall.request(1);

        await().until(() -> listener.trailers, Matchers.notNullValue());
        assertThat(listener.headers).isNotNull();
        assertThat(listener.headers.keys()).doesNotContain(
                HttpHeaderNames.STATUS.toString(),
                GrpcHeaderNames.GRPC_MESSAGE.toString(),
                GrpcHeaderNames.GRPC_STATUS.toString());
        assertThat(listener.headers.keys()).contains(key.name());
        assertThat(listener.headers.get(key)).isEqualTo("world");

        assertThat(listener.response).isNotNull();
        assertThat(listener.status.getCode()).isEqualTo(Code.OK);
    }
}
