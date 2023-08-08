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

package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executors;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeKey;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;

class GrpcServicePathTest {

    private static final AttributeKey<ExchangeType> EXCHANGE_TYPE =
            AttributeKey.valueOf(GrpcServicePathTest.class, "EXCHANGE_TYPE");

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final GrpcService grpcService = GrpcService.builder()
                                                       .addService(new TestServiceImpl(
                                                               Executors.newSingleThreadScheduledExecutor()))
                                                       .build();
            sb.serviceUnder("/grpc", grpcService);
            sb.decorator((delegate, ctx, req) -> {
                final GrpcService grpcService0 = ctx.config().service().as(GrpcService.class);
                final ExchangeType exchangeType =
                        grpcService0.exchangeType(ctx.routingContext());
                ctx.setAttr(EXCHANGE_TYPE, exchangeType);
                return delegate.serve(ctx, req);
            });
            sb.decorator(LoggingService.newDecorator());
            sb.service(grpcService, LoggingService.newDecorator());
        }
    };

    @CsvSource({ "/grpc", "/grpc/" })
    @ParameterizedTest
    void prefix(String path) {
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .pathPrefix(path)
                                                          .build(TestServiceBlockingStub.class);
        final SimpleResponse response = client.unaryCall(SimpleRequest.newBuilder()
                                                                      .setResponseSize(10)
                                                                      .build());
        assertThat(response.getPayload().getBody().size()).isEqualTo(10);
    }

    @CsvSource({ "/grpc", "/grpc/", "/" })
    @ParameterizedTest
    void exchangeTypeWithPathPrefix(String path) throws InterruptedException {
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .pathPrefix(path)
                                                          .build(TestServiceBlockingStub.class);
        client.unaryCall(SimpleRequest.newBuilder()
                                      .setResponseSize(10)
                                      .build());
        final RequestLog log = server.requestContextCaptor().take().log().whenComplete().join();
        assertThat(log.context().attr(EXCHANGE_TYPE)).isEqualTo(ExchangeType.UNARY);
    }
}
