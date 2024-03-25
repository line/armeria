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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;
import testing.grpc.UnitTestBarServiceGrpc.UnitTestBarServiceBlockingStub;
import testing.grpc.UnitTestBarServiceGrpc.UnitTestBarServiceImplBase;
import testing.grpc.UnitTestFooServiceGrpc.UnitTestFooServiceBlockingStub;
import testing.grpc.UnitTestFooServiceGrpc.UnitTestFooServiceImplBase;

class GrpcServiceBlockingTest {

    private static final AtomicBoolean blocking = new AtomicBoolean();

    private static final ScheduledExecutorService executor =
            new ScheduledThreadPoolExecutor(1, ThreadFactories.newThreadFactory("blocking-test", true));

    private static void checkCurrentThread() {
        if (Thread.currentThread().getName().contains("blocking-test")) {
            blocking.set(true);
        }
    }

    @BeforeEach
    void clear() {
        blocking.set(false);
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(5000)
              .decorator(LoggingService.newDecorator())
              .service(GrpcService.builder()
                                  .addService(new TestServiceImpl())
                                  .addService(new UnitTestFooServiceImpl())
                                  .addService(new UnitTestBarServiceImpl())
                                  .addService("/foo", new FooTestServiceImpl())
                                  .addService("/bar", new BarTestServiceImpl(),
                                              TestServiceGrpc.getUnaryCallMethod())
                                  .build())
              .blockingTaskExecutor(executor, true);
        }
    };

    @Test
    void nonBlockingCall() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);
        assertThat(client.unaryCall(SimpleRequest.getDefaultInstance())).isNotNull();
        assertThat(blocking).isFalse();
    }

    @Test
    void blockingOnClass() {
        final UnitTestFooServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), UnitTestFooServiceBlockingStub.class);
        assertThat(client.staticUnaryCall(SimpleRequest.getDefaultInstance())).isNotNull();
        assertThat(blocking).isTrue();

        blocking.set(false);

        assertThat(client.staticUnaryCall2(SimpleRequest.getDefaultInstance())).isNotNull();
        assertThat(blocking).isTrue();
    }

    @Test
    void blockingOnMethod() {
        final UnitTestBarServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), UnitTestBarServiceBlockingStub.class);
        assertThat(client.staticUnaryCall(SimpleRequest.getDefaultInstance())).isNotNull();
        assertThat(blocking).isTrue();

        blocking.set(false);

        assertThat(client.staticUnaryCall2(SimpleRequest.getDefaultInstance())).isNotNull();
        assertThat(blocking).isFalse();
    }

    @Test
    void prefixServiceBlockingOnMethod() {
        final TestServiceBlockingStub client = GrpcClients
                .builder(server.httpUri())
                .decorator((delegate, ctx, req) -> {
                    final String path = req.path();
                    final HttpRequest newReq = req.mapHeaders(
                            headers -> headers.toBuilder()
                                              .path(path.replace(
                                                      "armeria.grpc.testing.TestService",
                                                      "foo"))
                                              .build());
                    ctx.updateRequest(newReq);
                    return delegate.execute(ctx, newReq);
                })
                .build(TestServiceBlockingStub.class);
        assertThat(client.unaryCall(SimpleRequest.getDefaultInstance())).isNotNull();
        assertThat(blocking).isTrue();
    }

    @Test
    void solelyAddedMethodBlockingOnClass() {
        final TestServiceBlockingStub client = GrpcClients
                .builder(server.httpUri())
                .decorator((delegate, ctx, req) -> {
                    final HttpRequest newReq = req.mapHeaders(
                            headers -> headers.toBuilder().path("/bar").build());
                    ctx.updateRequest(newReq);
                    return delegate.execute(ctx, newReq);
                })
                .build(TestServiceBlockingStub.class);
        assertThat(client.unaryCall(SimpleRequest.getDefaultInstance())).isNotNull();
        assertThat(blocking).isTrue();
    }

    private static class TestServiceImpl extends TestServiceImplBase {

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("test user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    @Blocking
    private static class UnitTestFooServiceImpl extends UnitTestFooServiceImplBase {
        @Override
        public void staticUnaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            checkCurrentThread();
            responseObserver.onNext(SimpleResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void staticUnaryCall2(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            checkCurrentThread();
            responseObserver.onNext(SimpleResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    private static class UnitTestBarServiceImpl extends UnitTestBarServiceImplBase {

        @Override
        @Blocking
        public void staticUnaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            checkCurrentThread();
            responseObserver.onNext(SimpleResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void staticUnaryCall2(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            checkCurrentThread();
            responseObserver.onNext(SimpleResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    private static class FooTestServiceImpl extends TestServiceImplBase {

        @Override
        @Blocking
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            checkCurrentThread();
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("foo user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    @Blocking
    private static class BarTestServiceImpl extends TestServiceImplBase {

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            checkCurrentThread();
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("bar user")
                                                  .build());
            responseObserver.onCompleted();
        }

        @Override
        public void unaryCall2(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            checkCurrentThread();
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("not registered")
                                                  .build());
            responseObserver.onCompleted();
        }
    }
}
