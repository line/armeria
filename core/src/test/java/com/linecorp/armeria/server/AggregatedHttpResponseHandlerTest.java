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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.LinkedTransferQueue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class AggregatedHttpResponseHandlerTest {

    static final Queue<ByteBuf> responseBufs = new LinkedTransferQueue<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.decorator(LoggingService.newDecorator());

            sb.service("/hello", new HttpService() {
                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.UNARY;
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    assertThat(req).isInstanceOf(EmptyContentDecodedHttpRequest.class);
                    // Make sure that the stream was closed already.
                    assertThat(req.isOpen()).isFalse();

                    // Use a direct buffer body, so we can ensure there's no leak in the test.
                    final ByteBuf contentBuf =
                            Unpooled.directBuffer()
                                    .writeBytes("Hello".getBytes(StandardCharsets.UTF_8));
                    responseBufs.add(contentBuf);
                    return HttpResponse.of(ResponseHeaders.of(200), HttpData.wrap(contentBuf));
                }
            });

            sb.service("/echo", new HttpService() {
                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.UNARY;
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    assertThat(req).isInstanceOf(AggregatingDecodedHttpRequest.class);
                    // Make sure that the stream was closed already.
                    assertThat(req.isOpen()).isFalse();
                    return HttpResponse.of(req.aggregate().thenApply(agg -> {
                        return HttpResponse.of(agg.contentUtf8());
                    }));
                }
            });

            sb.service("/exception", new HttpService() {
                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.UNARY;
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    throw new IllegalStateException("something went wrong");
                }
            });

            sb.service("/status-exception", new HttpService() {
                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.UNARY;
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    throw HttpStatusException.of(HttpStatus.BAD_REQUEST,
                                                 new IllegalStateException("status-exception"));
                }
            });

            sb.service("/response-exception", new HttpService() {
                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.UNARY;
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    throw HttpResponseException.of(HttpResponse.of("Recovered"),
                                                   new IllegalArgumentException("response-exception"));
                }
            });

            sb.service("/failed", new HttpService() {
                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.UNARY;
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    throw HttpResponseException.of(
                            HttpResponse.ofFailure(new IllegalArgumentException("failed-response")));
                }
            });

            sb.service("/informational-trailers", new HttpService() {
                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.UNARY;
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(
                            ResponseHeaders.of(HttpStatus.CONTINUE),
                            ResponseHeaders.of(HttpStatus.CONTINUE),
                            ResponseHeaders.of(HttpStatus.OK),
                            HttpData.ofUtf8("more-headers"),
                            HttpHeaders.of("trailerA", "1"));
                }
            });
        }
    };

    @BeforeAll
    static void cleanResponseBufs() {
        responseBufs.clear();
    }

    @AfterAll
    static void verifyResponseBufs() {
        responseBufs.forEach(buf -> {
            assertThat(buf.refCnt()).isZero();
        });
    }

    @Test
    void shouldReturnEmptyBodyOnHead() throws Exception {
        final BlockingWebClient client = server.blockingWebClient();
        final AggregatedHttpResponse res = client.head("/hello");
        assertThat(res.headers().contentLength()).isEqualTo(5);
        assertThat(res.contentUtf8()).isEmpty();
    }

    @Test
    void echo() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.post("/echo", "Hello").aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo("Hello");

        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog requestLog = ctx.log().whenComplete().join();
        assertThat(requestLog.responseDurationNanos()).isPositive();
        assertThat(requestLog.responseCause()).isNull();
    }

    @Test
    void exception() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.get("/exception").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog requestLog = ctx.log().whenComplete().join();
        assertThat(requestLog.responseCause()).isInstanceOf(IllegalStateException.class)
                                              .hasMessage("something went wrong");
    }

    @Test
    void statusException() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.get("/status-exception").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog requestLog = ctx.log().whenComplete().join();
        assertThat(requestLog.responseCause()).isInstanceOf(IllegalStateException.class)
                                              .hasMessage("status-exception");
    }

    @Test
    void responseException() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.get("/response-exception").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("Recovered");

        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog requestLog = ctx.log().whenComplete().join();
        assertThat(requestLog.responseCause()).isInstanceOf(IllegalArgumentException.class)
                                              .hasMessage("response-exception");
    }

    @Test
    void failToRecover() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.get("/failed").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog requestLog = ctx.log().whenComplete().join();
        assertThat(requestLog.responseCause()).isInstanceOf(IllegalArgumentException.class)
                                              .hasMessage("failed-response");
    }

    @Test
    void informationalHeadersAndTrailers() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(LoggingClient.newDecorator())
                                          .build();
        final AggregatedHttpResponse response = client.get("/informational-trailers").aggregate().join();
        assertThat(response.informationals())
                .extracting(ResponseHeaders::status)
                .containsExactly(HttpStatus.CONTINUE, HttpStatus.CONTINUE);

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("more-headers");
        assertThat(response.trailers()).hasSize(1);
        assertThat(response.trailers().get("trailerA")).isEqualTo("1");
    }
}
