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

package com.linecorp.armeria.internal.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientOptionValue;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.grpc.GrpcClientOptions;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

class GrpcStatusMappingTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(
                    GrpcService.builder()
                               .addService(new TestServiceImpl())
                               .addExceptionMapping(A2Exception.class, Status.UNIMPLEMENTED)
                               .addExceptionMapping(A3Exception.class, Status.UNAUTHENTICATED)
                               .addExceptionMapping(ImmutableList.of(
                                       new SimpleImmutableEntry<>(B2Exception.class, Status.NOT_FOUND),
                                       new SimpleImmutableEntry<>(B1Exception.class, Status.UNAUTHENTICATED)))
                               .addExceptionMapping(A1Exception.class, Status.RESOURCE_EXHAUSTED)
                               .build());
        }
    };

    @Nullable
    private static final AtomicReference<RuntimeException> exceptionRef = new AtomicReference<>();

    @ArgumentsSource(ExceptionMappingsProvider.class)
    @ParameterizedTest
    void serverExceptionMapping(RuntimeException exception, Status status) {
        exceptionRef.set(exception);
        TestServiceBlockingStub client = Clients.newClient(server.httpUri(GrpcSerializationFormats.PROTO),
                                                           TestServiceBlockingStub.class);
        assertStatus(() -> client.emptyCall(Empty.getDefaultInstance()), status);
        assertStatus(() -> client.unaryCall(SimpleRequest.getDefaultInstance()), status);
    }

    @ArgumentsSource(ExceptionMappingsProvider.class)
    @ParameterizedTest
    void clientExceptionMapping(RuntimeException exception, Status status) {
        final ClientOptionValue<Iterable<? extends Entry<Class<? extends Throwable>, Status>>> exceptionMaps =
                GrpcClientOptions.GRPC_EXCEPTION_MAPPINGS.newValue(
                        ImmutableList.of(new SimpleImmutableEntry<>(exception.getClass(), status)));

        final TestServiceBlockingStub client =
                Clients.builder(server.httpUri(GrpcSerializationFormats.PROTO))
                       .decorator((delegate, ctx, req) -> {
                           throw exception;
                       })
                       .option(exceptionMaps)
                       .build(TestServiceBlockingStub.class);
        assertStatus(() -> client.emptyCall(Empty.getDefaultInstance()), status);
    }

    void assertStatus(ThrowingCallable task, Status status) {
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
