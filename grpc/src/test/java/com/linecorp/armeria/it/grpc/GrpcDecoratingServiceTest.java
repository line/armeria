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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;

class GrpcDecoratingServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(5000);
            sb.decorator(LoggingService.newDecorator());
            sb.service(GrpcService.builder()
                                  .addService(new FirstTestServiceImpl())
                                  .build());
            sb.serviceUnder("/grpc", GrpcService.builder()
                                                .addService(new SecondTestServiceImpl())
                                                .build());
        }
    };

    private static String FIRST_TEST_RESULT = "";
    private static String SECOND_TEST_RESULT = "";

    @Test
    void methodDecorators() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);
        client.unaryCall(SimpleRequest.getDefaultInstance());
        assertThat(FIRST_TEST_RESULT)
                .isEqualTo("FirstDecorator/MethodFirstDecorator");

        final TestServiceBlockingStub prefixClient = GrpcClients
                .builder(server.httpUri())
                .decorator((delegate, ctx, req) -> {
                    final String path = req.path();
                    final HttpRequest newReq = req.mapHeaders(
                            headers -> headers.toBuilder()
                                              .path(path.replace(
                                                      "armeria.grpc.testing.TestService",
                                                      "grpc/armeria.grpc.testing.TestService"))
                                              .build());
                    ctx.updateRequest(newReq);
                    return delegate.execute(ctx, newReq);
                })
                .build(TestServiceBlockingStub.class);
        prefixClient.unaryCall(SimpleRequest.getDefaultInstance());
        assertThat(SECOND_TEST_RESULT).isEqualTo("SecondDecorator/MethodSecondDecorator");
    }

    private static class FirstDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            FIRST_TEST_RESULT += "FirstDecorator/";
            return delegate.serve(ctx, req);
        }
    }

    private static class MethodFirstDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            FIRST_TEST_RESULT += "MethodFirstDecorator";
            return delegate.serve(ctx, req);
        }
    }

    private static class SecondDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            SECOND_TEST_RESULT += "SecondDecorator/";
            return delegate.serve(ctx, req);
        }
    }

    private static class MethodSecondDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            SECOND_TEST_RESULT += "MethodSecondDecorator";
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
