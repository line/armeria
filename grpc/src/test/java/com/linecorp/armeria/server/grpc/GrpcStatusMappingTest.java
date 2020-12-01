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

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

class GrpcStatusMappingTest {

    @RegisterExtension
    static ServerExtension serverWithMapping = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(
                    GrpcService.builder()
                               .addService(new TestServiceImpl())
                               .addExceptionMapping(A2Exception.class, Status.UNIMPLEMENTED)
                               .addExceptionMapping(A3Exception.class, Status.UNAUTHENTICATED)
                               .addExceptionMapping(B2Exception.class, Status.NOT_FOUND)
                               .addExceptionMapping(B1Exception.class, Status.UNAUTHENTICATED)
                               .addExceptionMapping(A1Exception.class, Status.RESOURCE_EXHAUSTED)
                               .build());
        }
    };

    @RegisterExtension
    static ServerExtension serverWithMappingFunction = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(
                    GrpcService.builder()
                               .addService(new TestServiceImpl())
                               .exceptionMapping(cause -> {
                                   if (cause instanceof A3Exception) {
                                       return Status.UNAUTHENTICATED;
                                   }
                                   if (cause instanceof A2Exception) {
                                       return Status.UNIMPLEMENTED;
                                   }
                                   if (cause instanceof A1Exception) {
                                       return Status.RESOURCE_EXHAUSTED;
                                   }

                                   if (cause instanceof B2Exception) {
                                       return Status.NOT_FOUND;
                                   }
                                   if (cause instanceof B1Exception) {
                                       return Status.UNAUTHENTICATED;
                                   }
                                   return null;
                               })
                               .build());
        }
    };

    @Nullable
    private static final AtomicReference<RuntimeException> exceptionRef = new AtomicReference<>();

    @ArgumentsSource(ExceptionMappingsProvider.class)
    @ParameterizedTest
    void serverExceptionMapping(RuntimeException exception, Status status) {
        exceptionRef.set(exception);
        final TestServiceBlockingStub client = Clients.newClient(
                serverWithMapping.httpUri(GrpcSerializationFormats.PROTO),
                TestServiceBlockingStub.class);
        assertStatus(() -> client.emptyCall(Empty.getDefaultInstance()), status);
        assertStatus(() -> client.unaryCall(SimpleRequest.getDefaultInstance()), status);
    }

    @ArgumentsSource(ExceptionMappingsProvider.class)
    @ParameterizedTest
    void serverExceptionWithMappingFunction(RuntimeException exception, Status status) {
        exceptionRef.set(exception);
        final TestServiceBlockingStub client = Clients.newClient(
                serverWithMappingFunction.httpUri(GrpcSerializationFormats.PROTO),
                TestServiceBlockingStub.class);
        assertStatus(() -> client.emptyCall(Empty.getDefaultInstance()), status);
        assertStatus(() -> client.unaryCall(SimpleRequest.getDefaultInstance()), status);
    }

    @Test
    void clientException_decorator() {
        final TestServiceBlockingStub client =
                Clients.builder(serverWithMapping.httpUri(GrpcSerializationFormats.PROTO))
                       .decorator((delegate, ctx, req) -> {
                           throw new UnhandledException();
                       })
                       .build(TestServiceBlockingStub.class);
        // Make sure that a client call is closed when a exception is raised in a decorator.
        assertStatus(() -> client.unaryCall2(SimpleRequest.getDefaultInstance()), Status.UNKNOWN);
    }

    @Test
    void clientExceptionMapping_interceptor() {
        final TestServiceBlockingStub client =
                Clients.builder(serverWithMapping.httpUri(GrpcSerializationFormats.PROTO))
                       .build(TestServiceBlockingStub.class)
                .withInterceptors(new ClientInterceptor() {
                    @Override
                    public <I, O> ClientCall<I, O> interceptCall(
                            MethodDescriptor<I, O> method, CallOptions callOptions, Channel next) {
                        return new SimpleForwardingClientCall<I, O>(next.newCall(method, callOptions)) {
                            @Override
                            public void start(Listener<O> responseListener, Metadata headers) {
                                super.start(new SimpleForwardingClientCallListener<O>(responseListener) {
                                    @Override
                                    public void onMessage(O message) {
                                        throw new UnhandledException();
                                    }
                                }, headers);
                            }
                        };
                    }
                });
        // Make sure that a client call is closed when a exception is raised in a client interceptor.
        assertStatus(() -> client.unaryCall2(SimpleRequest.getDefaultInstance()), Status.UNKNOWN);
    }

    private static void assertStatus(ThrowingCallable task, Status status) {
        final Throwable cause = catchThrowable(task);
        final StatusRuntimeException statusException = (StatusRuntimeException) cause;
        assertThat(statusException.getStatus().getCode()).isEqualTo(status.getCode());
    }

    private static class ExceptionMappingsProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(
                    Arguments.of(new A1Exception(), Status.RESOURCE_EXHAUSTED),
                    Arguments.of(new A2Exception(), Status.UNIMPLEMENTED),
                    Arguments.of(new A3Exception(), Status.UNAUTHENTICATED),
                    Arguments.of(new B2Exception(), Status.NOT_FOUND),
                    Arguments.of(new B1Exception(), Status.UNAUTHENTICATED),
                    Arguments.of(new UnhandledException(), Status.UNKNOWN));
        }
    }

    private static class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {

        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onError(exceptionRef.get());
        }

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            throw exceptionRef.get();
        }

        @Override
        public void unaryCall2(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    static class A1Exception extends RuntimeException {
        private static final long serialVersionUID = 7571363504756974018L;
    }

    static class A2Exception extends A1Exception {
        private static final long serialVersionUID = 6081251455350756572L;
    }

    static class A3Exception extends A2Exception {
        private static final long serialVersionUID = -5833147108943992843L;
    }

    static class B1Exception extends RuntimeException {
        private static final long serialVersionUID = 7171673234824312473L;
    }

    static class B2Exception extends B1Exception {
        private static final long serialVersionUID = -3517806360215313260L;
    }

    private static class UnhandledException extends RuntimeException {
        private static final long serialVersionUID = -2330757369375222959L;
    }
}
