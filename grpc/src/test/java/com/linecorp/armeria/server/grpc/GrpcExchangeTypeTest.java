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
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;

import io.grpc.MethodDescriptor;

class GrpcExchangeTypeTest {

    @ArgumentsSource(ExchangeTypeProvider.class)
    @ParameterizedTest
    void exchangeType(MethodDescriptor<?, ?> method, ExchangeType expectedExchangeType) {
        final TestServiceImpl testService = new TestServiceImpl(null);
        final GrpcService grpcService = GrpcService.builder()
                                                   .addService(testService)
                                                   .build();
        final ExchangeType exchangeType = grpcService.exchangeType(
                RequestHeaders.of(HttpMethod.POST, '/' + method.getFullMethodName()), null);
        assertThat(exchangeType).isEqualTo(expectedExchangeType);
    }

    @ArgumentsSource(ExchangeTypeWithGrpcWebTextProvider.class)
    @ParameterizedTest
    void exchangeTypeWithGrpcWebText(MethodDescriptor<?, ?> method, ExchangeType expectedExchangeType) {
        final TestServiceImpl testService = new TestServiceImpl(null);
        final GrpcService grpcService = GrpcService.builder()
                                                   .addService(testService)
                                                   .build();
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.POST, '/' + method.getFullMethodName())
                              .contentType(GrpcSerializationFormats.PROTO_WEB_TEXT.mediaType())
                              .build();
        final ExchangeType exchangeType = grpcService.exchangeType(headers, null);
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

        ExchangeType exchangeType = grpcService.exchangeType(framedHeaders, null);
        assertThat(exchangeType).isEqualTo(expectedExchangeType);

        final RequestHeaders unframedHeaders1 =
                RequestHeaders.builder(HttpMethod.POST, '/' + method.getFullMethodName())
                              .contentType(MediaType.JSON_UTF_8)
                              .build();
        exchangeType = grpcService.exchangeType(unframedHeaders1, null);
        assertThat(exchangeType).isEqualTo(ExchangeType.UNARY);

        final RequestHeaders unframedHeaders2 =
                RequestHeaders.builder(HttpMethod.POST, '/' + method.getFullMethodName())
                              .contentType(MediaType.PROTOBUF)
                              .build();
        exchangeType = grpcService.exchangeType(unframedHeaders2, null);
        assertThat(exchangeType).isEqualTo(ExchangeType.UNARY);

        final RequestHeaders unknownContentType =
                RequestHeaders.builder(HttpMethod.POST, '/' + method.getFullMethodName())
                              .contentType(MediaType.OCTET_STREAM)
                              .build();
        exchangeType = grpcService.exchangeType(unknownContentType, null);
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

    private static final class ExchangeTypeWithGrpcWebTextProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            // Some Base64 decoders such as JDK's Base64.getDecoder() cannot decode the concatenated
            // Base64-encoded chunk. AggregatedHttpResponse is disabled and only RESPONSE_STREAMING and
            // BIDI_STREAMING are supported.
            return Stream.of(
                    Arguments.of(TestServiceGrpc.getUnaryCallMethod(), ExchangeType.RESPONSE_STREAMING),
                    Arguments.of(TestServiceGrpc.getStreamingOutputCallMethod(),
                                 ExchangeType.RESPONSE_STREAMING),
                    Arguments.of(TestServiceGrpc.getStreamingInputCallMethod(), ExchangeType.BIDI_STREAMING),
                    Arguments.of(TestServiceGrpc.getFullDuplexCallMethod(), ExchangeType.BIDI_STREAMING)
            );
        }
    }
}
