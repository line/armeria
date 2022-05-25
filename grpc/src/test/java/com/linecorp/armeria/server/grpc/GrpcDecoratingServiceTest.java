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

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.Metrics.GaugeRequest;
import com.linecorp.armeria.grpc.testing.Metrics.GaugeResponse;
import com.linecorp.armeria.grpc.testing.MetricsServiceGrpc.MetricsServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.MetricsServiceGrpc.MetricsServiceImplBase;
import com.linecorp.armeria.grpc.testing.ReconnectServiceGrpc.ReconnectServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.ReconnectServiceGrpc.ReconnectServiceImplBase;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;

class GrpcDecoratingServiceTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(5000);
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl())
                                  .addService(new MetricsServiceImpl())
                                  .addService(new ReconnectServiceImpl())
                                  .addService("/foo", new FooTestServiceImpl())
                                  .addService("/bar", new BarTestServiceImpl(),
                                              TestServiceGrpc.getUnaryCallMethod())
                                  .build());
        }
    };

    private static final BlockingDeque<String> decorators = new LinkedBlockingDeque<>();

    @BeforeEach
    void setUp() {
        decorators.clear();
    }

    @Test
    void methodDecorators() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);
        assertThat(client.unaryCall(SimpleRequest.getDefaultInstance()).getUsername()).isEqualTo("test user");
        assertThat(decorators.poll()).isEqualTo("FirstDecorator");
        assertThat(decorators.poll()).isEqualTo("SecondDecorator");
        assertThat(decorators.poll()).isEqualTo("MethodFirstDecorator");
        assertThat(decorators.poll()).isEqualTo("MethodSecondDecorator");
        assertThat(decorators).isEmpty();
    }

    @Test
    void serviceDecorators() {
        final MetricsServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), MetricsServiceBlockingStub.class);
        assertThat(client.getGauge(GaugeRequest.getDefaultInstance()).getName()).isEqualTo("gauge");
        assertThat(decorators.poll()).isEqualTo("FifthDecorator");
        assertThat(decorators).isEmpty();
    }

    @Test
    void nonDecorators() {
        final ReconnectServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), ReconnectServiceBlockingStub.class);
        assertThat(client.start(Empty.getDefaultInstance())).isSameAs(Empty.getDefaultInstance());
        assertThat(decorators).isEmpty();
    }

    @Test
    void prefixService() {
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
        assertThat(client.unaryCall(SimpleRequest.getDefaultInstance()).getUsername()).isEqualTo("foo user");
        assertThat(decorators.poll()).isEqualTo("ThirdDecorator");
        assertThat(decorators.poll()).isEqualTo("MethodThirdDecorator");
        assertThat(decorators).isEmpty();
    }

    @Test
    void solelyAddedMethod() {
        final TestServiceBlockingStub client = GrpcClients
                .builder(server.httpUri())
                .decorator((delegate, ctx, req) -> {
                    final HttpRequest newReq = req.mapHeaders(
                            headers -> headers.toBuilder().path("/bar").build());
                    ctx.updateRequest(newReq);
                    return delegate.execute(ctx, newReq);
                })
                .build(TestServiceBlockingStub.class);
        assertThat(client.unaryCall(SimpleRequest.getDefaultInstance()).getUsername()).isEqualTo("bar user");
        assertThat(decorators.poll()).isEqualTo("FourthDecorator");
        assertThat(decorators.poll()).isEqualTo("MethodFourthDecorator");
        assertThat(decorators).isEmpty();
    }

    private static class FirstDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            decorators.offer("FirstDecorator");
            return delegate.serve(ctx, req);
        }
    }

    private static class SecondDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            decorators.offer("SecondDecorator");
            return delegate.serve(ctx, req);
        }
    }

    private static class ThirdDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            decorators.offer("ThirdDecorator");
            return delegate.serve(ctx, req);
        }
    }

    private static class FourthDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            decorators.offer("FourthDecorator");
            return delegate.serve(ctx, req);
        }
    }

    private static class FifthDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            decorators.offer("FifthDecorator");
            return delegate.serve(ctx, req);
        }
    }

    private static class MethodFirstDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            decorators.offer("MethodFirstDecorator");
            return delegate.serve(ctx, req);
        }
    }

    private static class MethodSecondDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            decorators.offer("MethodSecondDecorator");
            return delegate.serve(ctx, req);
        }
    }

    private static class MethodThirdDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            decorators.offer("MethodThirdDecorator");
            return delegate.serve(ctx, req);
        }
    }

    private static class MethodFourthDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            decorators.offer("MethodFourthDecorator");
            return delegate.serve(ctx, req);
        }
    }

    @Decorator(FirstDecorator.class)
    @Decorator(SecondDecorator.class)
    private static class TestServiceImpl extends TestServiceImplBase {

        @Override
        @Decorator(MethodFirstDecorator.class)
        @Decorator(MethodSecondDecorator.class)
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("test user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    @Decorator(ThirdDecorator.class)
    private static class FooTestServiceImpl extends TestServiceImplBase {

        @Override
        @Decorator(MethodThirdDecorator.class)
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("foo user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    @Decorator(FourthDecorator.class)
    private static class BarTestServiceImpl extends TestServiceImplBase {

        @Override
        @Decorator(MethodFourthDecorator.class)
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("bar user")
                                                  .build());
            responseObserver.onCompleted();
        }

        @Override
        public void unaryCall2(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("not registered")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    @Decorator(FifthDecorator.class)
    private static class MetricsServiceImpl extends MetricsServiceImplBase {

        @Override
        public void getGauge(GaugeRequest request, StreamObserver<GaugeResponse> responseObserver) {
            responseObserver.onNext(GaugeResponse.newBuilder().setName("gauge").build());
            responseObserver.onCompleted();
        }
    }

    private static class ReconnectServiceImpl extends ReconnectServiceImplBase {

        @Override
        public void start(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }
}
