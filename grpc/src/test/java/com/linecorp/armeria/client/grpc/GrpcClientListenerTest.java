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
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceStub;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
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

class GrpcClientListenerTest {

    private static final Key<String> key = InternalMetadata.keyOf("hello", Metadata.ASCII_STRING_MARSHALLER);

    private static final ServerInterceptor interceptor = new ServerInterceptor() {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            return next.startCall(new SimpleForwardingServerCall<ReqT, RespT>(call) {
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
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl(Executors.newSingleThreadScheduledExecutor()) {
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
        }
    };

    private final AtomicReference<Metadata> trailersRef = new AtomicReference<>();
    private final AtomicReference<Metadata> headersRef = new AtomicReference<>();
    private final AtomicReference<Status> statusRef = new AtomicReference<>();
    private final AtomicReference<SimpleResponse> responseRef = new AtomicReference<>();

    private final Listener<SimpleResponse> listener = new Listener<SimpleResponse>() {
        @Override
        public void onHeaders(Metadata headers) {
            headersRef.set(headers);
        }

        @Override
        public void onMessage(SimpleResponse message) {
            responseRef.set(message);
        }

        @Override
        public void onClose(Status status, Metadata trailers) {
            statusRef.set(status);
            trailersRef.set(trailers);
        }
    };

    @BeforeEach
    void beforeEach() {
        trailersRef.set(null);
        headersRef.set(null);
        statusRef.set(null);
        responseRef.set(null);
    }

    @Test
    void failedProtocolResponse() {
        final TestServiceStub client = GrpcClients.builder(server.httpUri().resolve("/fail/"))
                                                  .build(TestServiceStub.class);
        final ClientCall<SimpleRequest, SimpleResponse> unaryCall =
                client.getChannel().newCall(TestServiceGrpc.getUnaryCallMethod(), CallOptions.DEFAULT);

        unaryCall.start(listener, new Metadata());

        unaryCall.sendMessage(SimpleRequest.getDefaultInstance());
        unaryCall.halfClose();
        // Send 1 request and expect to receive a message and trailers
        unaryCall.request(1);

        // both headers/response is not set
        await().untilAtomic(trailersRef, Matchers.notNullValue());
        assertThat(trailersRef.get().keys()).isEmpty();
        assertThat(headersRef.get()).isNull();
        assertThat(responseRef.get()).isNull();
        assertThat(statusRef.get().getCode()).isEqualTo(Code.UNKNOWN);
        assertThat(statusRef.get().getDescription()).isEqualTo("HTTP status code 204");
    }

    @Test
    void requestOneAndReceiveMessageAndTrailers() {
        final TestServiceStub client = GrpcClients.builder(server.httpUri())
                                                  .build(TestServiceStub.class);
        final ClientCall<SimpleRequest, SimpleResponse> unaryCall =
                client.getChannel().newCall(TestServiceGrpc.getUnaryCallMethod(), CallOptions.DEFAULT);

        unaryCall.start(listener, new Metadata());

        unaryCall.sendMessage(SimpleRequest.getDefaultInstance());
        unaryCall.halfClose();
        // Send 1 request and expect to receive a message and trailers
        unaryCall.request(1);

        await().untilAtomic(trailersRef, Matchers.notNullValue());
        assertThat(headersRef.get()).isNotNull();
        assertHeaders(headersRef.get());
        assertThat(responseRef.get()).isNotNull();
        assertThat(statusRef.get().getCode()).isEqualTo(Code.OK);
    }

    private static void assertHeaders(Metadata metadata) {
        assertThat(metadata.keys()).doesNotContain(
                HttpHeaderNames.STATUS.toString(),
                GrpcHeaderNames.GRPC_MESSAGE.toString(),
                GrpcHeaderNames.GRPC_STATUS.toString());
        assertThat(metadata.keys()).contains(key.name());
        assertThat(metadata.get(key)).isEqualTo("world");
    }
}
