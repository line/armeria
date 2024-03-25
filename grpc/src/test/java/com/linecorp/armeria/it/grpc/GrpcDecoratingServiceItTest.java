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

package com.linecorp.armeria.it.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class GrpcDecoratingServiceItTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(5000);
            sb.decorator(LoggingService.newDecorator());
            sb.decorator((delegate, ctx, req) -> {
                // We can aggregate request if it's not a streaming request.
                req.aggregate();
                return delegate.serve(ctx, req);
            });
            sb.service(GrpcService.builder()
                                  .addService(new FirstTestServiceImpl())
                                  .build());
            sb.serviceUnder("/grpc", GrpcService.builder()
                                                .addService(new SecondTestServiceImpl())
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
        client.unaryCall(SimpleRequest.getDefaultInstance());
        final String first = decorators.poll();
        final String second = decorators.poll();
        assertThat(first).isEqualTo("FirstDecorator");
        assertThat(second).isEqualTo("MethodFirstDecorator");
        assertThat(decorators).isEmpty();
    }

    @Test
    void methodDecoratorsWithPrefix() {
        final TestServiceBlockingStub prefixClient = GrpcClients.builder(server.httpUri())
                                                                .responseTimeoutMillis(0)
                                                                .pathPrefix("/grpc")
                                                                .build(TestServiceBlockingStub.class);
        prefixClient.unaryCall(SimpleRequest.getDefaultInstance());
        final String first = decorators.poll();
        final String second = decorators.poll();
        assertThat(first).isEqualTo("SecondDecorator");
        assertThat(second).isEqualTo("MethodSecondDecorator");
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

    private static class MethodFirstDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            decorators.offer("MethodFirstDecorator");
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

    private static class MethodSecondDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            decorators.offer("MethodSecondDecorator");
            return delegate.serve(ctx, req);
        }
    }

    @Decorator(FirstDecorator.class)
    private static class FirstTestServiceImpl extends TestServiceImplBase {

        @Override
        @Decorator(MethodFirstDecorator.class)
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("test user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    @Decorator(SecondDecorator.class)
    private static class SecondTestServiceImpl extends TestServiceImplBase {

        @Override
        @Decorator(MethodSecondDecorator.class)
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("test user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }
}
