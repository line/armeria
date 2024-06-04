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

import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class FallbackServiceTest {

    @Nullable
    private static volatile Boolean lastRouteWasFallback;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of("Hello, world!"));
            sb.decorator((delegate, ctx, req) -> {
                lastRouteWasFallback = ctx.config().route().isFallback();
                return delegate.serve(ctx, req)
                               // Make sure that FallbackService does not throw an exception
                               .mapHeaders(headers -> headers.toBuilder().set("x-trace-id", "foo").build());
            });
        }
    };

    @RegisterExtension
    static ServerExtension lengthLimitServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.maxRequestLength(100);
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @RegisterExtension
    static ServerExtension lengthLimitServerWithDecorator = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.maxRequestLength(100);
            sb.decorator((delegate, ctx, req) -> {
                return HttpResponse.delayed(HttpResponse.of(200), Duration.ofSeconds(5));
            });
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    private static BlockingWebClient webClient;

    @BeforeAll
    static void initWebClient() {
        webClient = BlockingWebClient.of(server.httpUri());
    }

    @BeforeEach
    void resetLastRouteWasFallback() {
        lastRouteWasFallback = null;
    }

    @Test
    void matched() {
        final AggregatedHttpResponse res = webClient.get("/");
        assertThat(res.headers().status()).isSameAs(HttpStatus.OK);
        assertThat(lastRouteWasFallback).isFalse();
        assertThat(res.headers().get("x-trace-id")).isEqualTo("foo");
    }

    @Test
    void unmatched() {
        final AggregatedHttpResponse res = webClient.get("/404");
        assertThat(res.headers().status()).isSameAs(HttpStatus.NOT_FOUND);
        assertThat(lastRouteWasFallback).isTrue();
        assertThat(res.headers().get("x-trace-id")).isEqualTo("foo");
    }

    @Test
    void matchedPreflight() {
        final AggregatedHttpResponse res = webClient.execute(preflightHeaders("/"));
        assertThat(res.headers().status()).isSameAs(HttpStatus.OK);
        assertThat(lastRouteWasFallback).isFalse();
        assertThat(res.headers().get("x-trace-id")).isEqualTo("foo");
    }

    @Test
    void unmatchedPreflight() {
        final AggregatedHttpResponse res = webClient.execute(preflightHeaders("/404"));
        assertThat(res.headers().status()).isSameAs(HttpStatus.FORBIDDEN);
        assertThat(lastRouteWasFallback).isTrue();
        assertThat(res.headers().get("x-trace-id")).isEqualTo("foo");
    }

    @ValueSource(strings = { "H1C", "H2C" })
    @ParameterizedTest
    void maxContentLengthWithFallbackService(SessionProtocol protocol) throws InterruptedException {
        final HttpRequestWriter streaming = HttpRequest.streaming(HttpMethod.POST, "/not-exist");
        for (int i = 0; i < 4; i++) {
            streaming.write(HttpData.ofUtf8(Strings.repeat("a", 30)));
        }
        streaming.close();
        final HttpResponse response = WebClient.builder(lengthLimitServer.uri(protocol))
                                               .build()
                                               .execute(streaming);

        // FallbackService to return a 404 Not Found response before the request payload exceeds the maximum
        // allowed length.
        final AggregatedHttpResponse agg = response.aggregate().join();
        assertThat(agg.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(agg.contentUtf8()).startsWith("Status: 404\n");
        final ServiceRequestContext sctx = lengthLimitServer.requestContextCaptor().take();
        final RequestLog log = sctx.log().whenComplete().join();
        // Make sure that the response was correctly logged.
        assertThat(log.responseStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @ValueSource(strings = { "H1C", "H2C" })
    @ParameterizedTest
    void maxContentLengthWithLateResponse(SessionProtocol protocol) throws InterruptedException {
        final HttpRequestWriter streaming = HttpRequest.streaming(HttpMethod.POST, "/not-exist");
        final HttpResponse response = WebClient.builder(lengthLimitServerWithDecorator.uri(protocol))
                                               .build()
                                               .execute(streaming);
        for (int i = 0; i < 4; i++) {
            streaming.write(HttpData.ofUtf8(Strings.repeat("a", 30)));
        }
        streaming.close();

        // If the request payload exceeds the maximum allowed length for non-exist paths and `FallbackService`
        // has not returned a response yet, Http{1,2}RequestDecoder will send a 413 Request Entity Too Large
        // response instead.
        final AggregatedHttpResponse agg = response.aggregate().join();
        assertThat(agg.status()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        assertThat(agg.contentUtf8()).startsWith("Status: 413\n");
        final ServiceRequestContext sctx = lengthLimitServerWithDecorator.requestContextCaptor().take();
        final RequestLog log = sctx.log().whenComplete().join();
        // Make sure that the response was correctly logged.
        assertThat(log.responseStatus()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
    }

    private static RequestHeaders preflightHeaders(String path) {
        return RequestHeaders.of(HttpMethod.OPTIONS, path,
                                 HttpHeaderNames.ACCEPT, "utf-8",
                                 HttpHeaderNames.ORIGIN, "http://example.com",
                                 HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "GET");
    }
}
