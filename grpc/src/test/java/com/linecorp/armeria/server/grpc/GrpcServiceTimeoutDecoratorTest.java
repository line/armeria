/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

/**
 * Makes sure a timeout set by a decorator survives, which is what
 * {@code useClientTimeoutHeader(false)} used to guarantee by leaving the context untouched.
 */
class GrpcServiceTimeoutDecoratorTest {

    private static final long SERVICE_TIMEOUT_MILLIS = 2000;
    private static final long DECORATOR_TIMEOUT_MILLIS = 7000;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(SERVICE_TIMEOUT_MILLIS);
            sb.service(GrpcService.builder()
                                  .useClientTimeoutHeader(false)
                                  .addService(new TimeoutReportingService())
                                  .build());
            sb.decorator((delegate, ctx, req) -> {
                ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_NOW, DECORATOR_TIMEOUT_MILLIS);
                return delegate.serve(ctx, req);
            });
        }
    };

    @Test
    void decoratorTimeoutSurvivesWithoutHeader() {
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .responseTimeoutMillis(0)
                                                          .build(TestServiceBlockingStub.class);
        assertThat(requestTimeoutMillis(client)).isEqualTo(DECORATOR_TIMEOUT_MILLIS);
    }

    @Test
    void decoratorTimeoutSurvivesWithHeader() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);
        assertThat(requestTimeoutMillis(client.withDeadlineAfter(30, TimeUnit.SECONDS)))
                .isEqualTo(DECORATOR_TIMEOUT_MILLIS);
    }

    private static long requestTimeoutMillis(TestServiceBlockingStub client) {
        return Long.parseLong(client.unaryCall(SimpleRequest.getDefaultInstance()).getUsername());
    }

    private static class TimeoutReportingService extends TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            final long timeoutMillis = ServiceRequestContext.current().requestTimeoutMillis();
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername(String.valueOf(timeoutMillis))
                                                  .build());
            responseObserver.onCompleted();
        }
    }
}
