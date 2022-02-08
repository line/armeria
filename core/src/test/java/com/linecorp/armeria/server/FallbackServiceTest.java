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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
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
                return delegate.serve(ctx, req);
            });
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
    }

    @Test
    void unmatched() {
        final AggregatedHttpResponse res = webClient.get("/404");
        assertThat(res.headers().status()).isSameAs(HttpStatus.NOT_FOUND);
        assertThat(lastRouteWasFallback).isTrue();
    }

    @Test
    void matchedPreflight() {
        final AggregatedHttpResponse res = webClient.execute(preflightHeaders("/"));
        assertThat(res.headers().status()).isSameAs(HttpStatus.OK);
        assertThat(lastRouteWasFallback).isFalse();
    }

    @Test
    void unmatchedPreflight() {
        final AggregatedHttpResponse res = webClient.execute(preflightHeaders("/404"));
        assertThat(res.headers().status()).isSameAs(HttpStatus.FORBIDDEN);
        assertThat(lastRouteWasFallback).isTrue();
    }

    private static RequestHeaders preflightHeaders(String path) {
        return RequestHeaders.of(HttpMethod.OPTIONS, path,
                                 HttpHeaderNames.ACCEPT, "utf-8",
                                 HttpHeaderNames.ORIGIN, "http://example.com",
                                 HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "GET");
    }
}
