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
 * under the License
 */

package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;

class GrpcRetryWithCircuitBreakerTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(0);
            sb.idleTimeoutMillis(0);
            sb.decorator(LoggingService.newDecorator());
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl())
                                  .build());
        }
    };

    @Test
    void retryWithCircuitBreaker() {
        final RetryRuleWithContent<HttpResponse> retryRuleWithContent =
                RetryRuleWithContent.<HttpResponse>builder()
                                    .onStatusClass(HttpStatusClass.SERVER_ERROR)
                                    .onResponseTrailers((ctx, trailers) -> {
                                        final String status = trailers.get(GrpcHeaderNames.GRPC_STATUS);
                                        return !"0".equals(status);
                                    })
                                    .thenBackoff();
        final CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaultName();
        final CircuitBreakerRuleWithContent<HttpResponse> cbRuleWithContent =
                CircuitBreakerRuleWithContent.<HttpResponse>builder()
                                             .onStatusClass(HttpStatusClass.SERVER_ERROR)
                                             .onResponseTrailers((ctx, trailers) -> {
                                                 final String status =
                                                         trailers.get(GrpcHeaderNames.GRPC_STATUS);
                                                 return !"0".equals(status);
                                             })
                                             .thenFailure();
        final TestServiceBlockingStub client =
                GrpcClients.builder(server.httpUri())
                           .decorator(LoggingClient.newDecorator())
                           .decorator(RetryingClient.newDecorator(retryRuleWithContent))
                           .decorator(CircuitBreakerClient.newDecorator(circuitBreaker, cbRuleWithContent))
                           .build(TestServiceBlockingStub.class);

        // Make sure to complete a call successfully
        assertThat(client.unaryCall(SimpleRequest.getDefaultInstance()).getUsername())
                .isEqualTo("my name");
    }

    private static final class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder().setUsername("my name").build());
            responseObserver.onCompleted();
        }
    }
}
