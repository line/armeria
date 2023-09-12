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

import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.MethodDescriptor;
import testing.grpc.TestServiceGrpc;

class GrpcExchangeTypeTest {

    @ArgumentsSource(ExchangeTypeProvider.class)
    @ParameterizedTest
    void exchangeType(MethodDescriptor<?, ?> method, ExchangeType expectedExchangeType) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, '/' + method.getFullMethodName());
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(headers));
        final TestServiceImpl testService = new TestServiceImpl(null);
        final GrpcService grpcService = GrpcService.builder()
                                                   .addService(testService)
                                                   .build();
        final ExchangeType exchangeType = grpcService.exchangeType(ctx.routingContext());
        assertThat(exchangeType).isEqualTo(expectedExchangeType);
    }

    @ArgumentsSource(ExchangeTypeProvider.class)
    @ParameterizedTest
    void exchangeTypeWithUnframed(MethodDescriptor<?, ?> method, ExchangeType expectedExchangeType) {
        final TestServiceImpl testService = new TestServiceImpl(null);
        final GrpcService grpcService = GrpcService.builder()
                                                   .addService(testService)
                                                   .enableUnframedRequests(true)
                                                   .build();

        final RequestHeaders framedHeaders =
                RequestHeaders.builder(HttpMethod.POST, '/' + method.getFullMethodName())
                              .contentType(GrpcSerializationFormats.PROTO.mediaType())
                              .build();

        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(framedHeaders));
        ExchangeType exchangeType = grpcService.exchangeType(ctx.routingContext());
        assertThat(exchangeType).isEqualTo(expectedExchangeType);

        final RequestHeaders unframedHeaders1 =
                RequestHeaders.builder(HttpMethod.POST, '/' + method.getFullMethodName())
                              .contentType(MediaType.JSON_UTF_8)
                              .build();

        final ServiceRequestContext unframedCtx1 = ServiceRequestContext.of(HttpRequest.of(unframedHeaders1));
        exchangeType = grpcService.exchangeType(unframedCtx1.routingContext());
        assertThat(exchangeType).isEqualTo(ExchangeType.UNARY);

        final RequestHeaders unframedHeaders2 =
                RequestHeaders.builder(HttpMethod.POST, '/' + method.getFullMethodName())
                              .contentType(MediaType.PROTOBUF)
                              .build();
        final ServiceRequestContext unframedCtx2 = ServiceRequestContext.of(HttpRequest.of(unframedHeaders2));
        exchangeType = grpcService.exchangeType(unframedCtx2.routingContext());
        assertThat(exchangeType).isEqualTo(ExchangeType.UNARY);

        final RequestHeaders unknownContentType =
                RequestHeaders.builder(HttpMethod.POST, '/' + method.getFullMethodName())
                              .contentType(MediaType.OCTET_STREAM)
                              .build();
        final ServiceRequestContext unknownCtx = ServiceRequestContext.of(HttpRequest.of(unknownContentType));
        exchangeType = grpcService.exchangeType(unknownCtx.routingContext());
        assertThat(exchangeType).isEqualTo(ExchangeType.BIDI_STREAMING);
    }

    private static final class ExchangeTypeProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(
                    Arguments.of(TestServiceGrpc.getUnaryCallMethod(), ExchangeType.UNARY),
                    Arguments.of(TestServiceGrpc.getStreamingOutputCallMethod(),
                                 ExchangeType.RESPONSE_STREAMING),
                    Arguments.of(TestServiceGrpc.getStreamingInputCallMethod(), ExchangeType.REQUEST_STREAMING),
                    Arguments.of(TestServiceGrpc.getFullDuplexCallMethod(), ExchangeType.BIDI_STREAMING)
            );
        }
    }
}
