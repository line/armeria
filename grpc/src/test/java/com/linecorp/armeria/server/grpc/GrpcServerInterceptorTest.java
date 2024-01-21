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

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.ClientInterceptor;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class GrpcServerInterceptorTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl(executor))
                                  .intercept(NoPassInterceptor.INSTANCE)
                                  .build());

            sb.serviceUnder("/non-blocking", GrpcService.builder()
                                                        .addService(new TestServiceImpl(executor))
                                                        .intercept(new NonBlockingInterceptor())
                                                        .build());

            sb.serviceUnder("/blocking", GrpcService.builder()
                                                    .addService(new TestServiceImpl(executor))
                                                    .intercept(new BlockingInterceptor())
                                                    .useBlockingTaskExecutor(true)
                                                    .build());

            sb.serviceUnder("/annotated", GrpcService.builder()
                                                     .addService(new AnnotatedInterceptorServiceImpl())
                                                     .intercept(new InterceptorAddFoo())
                                                     .build());
        }
    };

    @Test
    void closeCallByInterceptor() {
        final TestServiceBlockingStub client =
                GrpcClients.builder(server.httpUri())
                           .build(TestServiceBlockingStub.class);
        final Throwable cause = catchThrowable(() -> client.unaryCall(SimpleRequest.getDefaultInstance()));
        assertThat(cause).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) cause).getStatus()).isEqualTo(Status.PERMISSION_DENIED);
    }

    @Test
    void runInterceptorOnBlockingExecutor() {
        final TestServiceBlockingStub nonBlocking =
                GrpcClients.builder(server.httpUri())
                           .pathPrefix("/non-blocking")
                           .build(TestServiceBlockingStub.class);
        nonBlocking.emptyCall(Empty.getDefaultInstance());

        final TestServiceBlockingStub blocking =
                GrpcClients.builder(server.httpUri())
                           .pathPrefix("/blocking")
                           .build(TestServiceBlockingStub.class);
        blocking.emptyCall(Empty.getDefaultInstance());
    }

    @Test
    void annotatedInterceptorShouldWork() {
        AtomicReference<Metadata> headersCapture = new AtomicReference<>();
        AtomicReference<Metadata> trailersCapture = new AtomicReference<>();
        ClientInterceptor clientInterceptor = MetadataUtils.newCaptureMetadataInterceptor(headersCapture,
                                                                                          trailersCapture);
        final TestServiceBlockingStub client =
                GrpcClients.builder(server.httpUri())
                           .pathPrefix("/annotated")
                           .intercept(clientInterceptor)
                           .build(TestServiceBlockingStub.class);
        final SimpleRequest request = SimpleRequest.newBuilder().build();
        client.unaryCall(request);
        assertThat(headersCapture.get().get(TEST_HEADER)).isEqualTo("foobarqux");

        client.unaryCall2(request);
        assertThat(headersCapture.get().get(TEST_HEADER)).isEqualTo("foobar");
    }

    private static class NoPassInterceptor implements ServerInterceptor {

        private static final NoPassInterceptor INSTANCE = new NoPassInterceptor();

        private static final Listener<Object> NOOP_LISTENER = new ServerCall.Listener<Object>() {};

        @Override
        public <I, O> Listener<I> interceptCall(ServerCall<I, O> call, Metadata metadata,
                                                ServerCallHandler<I, O> next) {
            call.close(Status.PERMISSION_DENIED, metadata);
            @SuppressWarnings("unchecked")
            final Listener<I> cast = (Listener<I>) NOOP_LISTENER;
            return cast;
        }
    }

    private static class BlockingInterceptor implements ServerInterceptor {

        @Override
        public <I, O> Listener<I> interceptCall(ServerCall<I, O> call, Metadata metadata,
                                                ServerCallHandler<I, O> next) {
            // Make sure the current thread is context-aware.
            final ServiceRequestContext ctx = ServiceRequestContext.current();
            assertThat(ctx.eventLoop().inEventLoop()).isFalse();
            return next.startCall(call, metadata);
        }
    }

    private static class NonBlockingInterceptor implements ServerInterceptor {

        @Override
        public <I, O> Listener<I> interceptCall(ServerCall<I, O> call, Metadata metadata,
                                                ServerCallHandler<I, O> next) {
            // Make sure the current thread is context-aware.
            final ServiceRequestContext ctx = ServiceRequestContext.current();
            assertThat(ctx.eventLoop().inEventLoop()).isTrue();
            return next.startCall(call, metadata);
        }
    }

    private static final Metadata.Key<String> TEST_HEADER = Metadata.Key.of("test",
                                                                            Metadata.ASCII_STRING_MARSHALLER);

    private static <REQ, RESP> ServerCall<REQ, RESP> addToHeader(String suffix, ServerCall<REQ, RESP> call) {
        return new SimpleForwardingServerCall<REQ, RESP>(call) {
            @Override
            public void sendHeaders(Metadata responseHeaders) {
                final String testHeader;
                if (responseHeaders.get(TEST_HEADER) != null) {
                    testHeader = responseHeaders.get(TEST_HEADER);
                } else {
                    testHeader = "";
                }

                responseHeaders.put(TEST_HEADER, testHeader + suffix);
                super.sendHeaders(responseHeaders);
            }
        };
    }

    private static class InterceptorAddFoo implements ServerInterceptor {
        @Override
        public <REQ, RESP> Listener<REQ> interceptCall(ServerCall<REQ, RESP> call, Metadata headers,
                                                       ServerCallHandler<REQ, RESP> next) {
            return next.startCall(addToHeader("foo", call), headers);
        }
    }

    private static class InterceptorAddBar implements ServerInterceptor {
        @Override
        public <REQ, RESP> Listener<REQ> interceptCall(ServerCall<REQ, RESP> call, Metadata headers,
                                                       ServerCallHandler<REQ, RESP> next) {

            return next.startCall(addToHeader("bar", call), headers);
        }
    }

    private static class InterceptorAddQux implements ServerInterceptor {
        @Override
        public <REQ, RESP> Listener<REQ> interceptCall(ServerCall<REQ, RESP> call, Metadata headers,
                                                       ServerCallHandler<REQ, RESP> next) {

            return next.startCall(addToHeader("qux", call), headers);
        }
    }


    @GrpcInterceptor(InterceptorAddBar.class)
    private static class AnnotatedInterceptorServiceImpl extends TestServiceImplBase {

        @Override
        @GrpcInterceptor(InterceptorAddQux.class)
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder().build());
            responseObserver.onCompleted();
        }

        @Override
        public void unaryCall2(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder() .build());
            responseObserver.onCompleted();
        }
    }
}
