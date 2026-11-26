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

package com.linecorp.armeria.server.encoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.encoding.StreamEncoderFactories;
import com.linecorp.armeria.common.encoding.StreamEncoderFactory;
import com.linecorp.armeria.internal.testing.ImmediateEventLoop;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBufOutputStream;

class EncodingServiceTest {

    @Test
    void exchangeType() {
        final EncodingService encodingService = EncodingService.newDecorator().apply(new HttpService() {
            @Override
            public ExchangeType exchangeType(RoutingContext routingContext) {
                return ExchangeType.UNARY;
            }

            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                return null;
            }
        });

        final ExchangeType exchangeType = encodingService.exchangeType(null);

        // Should not return ExchangeType.UNARY to avoid aggregation and preserve the compressed chunks.
        assertThat(exchangeType).isEqualTo(ExchangeType.BIDI_STREAMING);
    }

    @Test
    void appliesCustomEncoderFactoryWithBuilder() throws Exception {
        final StreamEncoderFactory customBrotliEncoderFactory = spy(new StreamEncoderFactory() {
            @Override
            public String encodingHeaderValue() {
                return "br";
            }

            @Override
            public ByteBufOutputStream newEncoder(ByteBufOutputStream os) {
                return new ByteBufOutputStream(os.buffer());
            }
        });

        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                                 HttpHeaderNames.ACCEPT_ENCODING,
                                                                 "deflate;q=0.5, br;q=0.9"));
        final ServiceRequestContext sctx = ServiceRequestContext.builder(req)
                                                                .eventLoop(ImmediateEventLoop.INSTANCE)
                                                                .build();
        final HttpResponse res = HttpResponse.of(
                HttpStatus.OK,
                MediaType.PLAIN_TEXT,
                String.copyValueOf(new char[42])
        );

        final HttpService mockDelegate = mock(HttpService.class);
        when(mockDelegate.serve(sctx, req)).thenReturn(res);
        final EncodingService encodingService = EncodingService
                .builder()
                .minBytesToForceChunkedEncoding(42)
                .encoderFactories(
                        StreamEncoderFactories.DEFLATE,
                        customBrotliEncoderFactory)
                .build(mockDelegate);

        encodingService.serve(sctx, req).aggregate().get();

        verify(mockDelegate, times(1)).serve(sctx, req);
        verify(customBrotliEncoderFactory, atLeastOnce()).encodingHeaderValue();
        verify(customBrotliEncoderFactory, times(1)).newEncoder(any(ByteBufOutputStream.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "application/json", "application/json; charset=utf-8",
            "application/xml", "application/xml; charset=utf-8",
            "application/javascript", "application/javascript; charset=utf-8"
    })
    void encodesDefaultContentTypesRegardlessOfCharset(String contentType) throws Exception {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                                 HttpHeaderNames.ACCEPT_ENCODING, "gzip"));
        final ServiceRequestContext sctx = ServiceRequestContext.builder(req)
                                                                .eventLoop(ImmediateEventLoop.INSTANCE)
                                                                .build();
        final HttpService delegate = (ctx, unused) -> HttpResponse.of(
                HttpStatus.OK, MediaType.parse(contentType), String.copyValueOf(new char[2048]));

        final AggregatedHttpResponse res =
                EncodingService.newDecorator().apply(delegate).serve(sctx, req).aggregate().get();

        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
    }

    @Test
    void encodesOfJson() throws Exception {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                                 HttpHeaderNames.ACCEPT_ENCODING, "gzip"));
        final ServiceRequestContext sctx = ServiceRequestContext.builder(req)
                                                                .eventLoop(ImmediateEventLoop.INSTANCE)
                                                                .build();
        final HttpService delegate = (ctx, unused) -> HttpResponse.ofJson(new int[1024]);

        final AggregatedHttpResponse res =
                EncodingService.newDecorator().apply(delegate).serve(sctx, req).aggregate().get();

        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
    }
}
