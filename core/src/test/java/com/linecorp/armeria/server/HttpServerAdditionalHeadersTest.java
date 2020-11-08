/*
 * Copyright 2019 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpServerAdditionalHeadersTest {

    private static final AtomicReference<RequestLog> logHolder = new AtomicReference<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/headers_merged", (ctx, req) -> {
                addBadHeaders(ctx);
                ctx.setAdditionalResponseTrailer("foo", "bar");
                return HttpResponse.of(HttpStatus.NO_CONTENT);
            });
            sb.service("/headers_and_trailers", (ctx, req) -> {
                addBadHeaders(ctx);
                return HttpResponse.of("headers and trailers");
            });
            sb.service("/informational", (ctx, req) -> {
                ctx.setAdditionalResponseHeader("foo", "bar");
                ctx.log().whenComplete().thenAccept(logHolder::set);
                return HttpResponse.of(ResponseHeaders.of(HttpStatus.CONTINUE),
                                       ResponseHeaders.of(HttpStatus.OK));
            });
        }

        private void addBadHeaders(ServiceRequestContext ctx) {
            ctx.mutateAdditionalResponseHeaders(
                    mutator -> mutator.add(HttpHeaderNames.SCHEME, "https"));
            ctx.mutateAdditionalResponseHeaders(
                    mutator -> mutator.add(HttpHeaderNames.STATUS, "100"));
            ctx.mutateAdditionalResponseHeaders(
                    mutator -> mutator.add(HttpHeaderNames.METHOD, "CONNECT"));
            ctx.mutateAdditionalResponseHeaders(
                    mutator -> mutator.add(HttpHeaderNames.PATH, "/foo"));
            ctx.mutateAdditionalResponseTrailers(
                    mutator -> mutator.add(HttpHeaderNames.SCHEME, "https"));
            ctx.mutateAdditionalResponseTrailers(
                    mutator -> mutator.add(HttpHeaderNames.STATUS, "100"));
            ctx.mutateAdditionalResponseTrailers(
                    mutator -> mutator.add(HttpHeaderNames.METHOD, "CONNECT"));
            ctx.mutateAdditionalResponseTrailers(
                    mutator -> mutator.add(HttpHeaderNames.PATH, "/foo"));
            ctx.mutateAdditionalResponseTrailers(
                    mutator -> mutator.add(HttpHeaderNames.TRANSFER_ENCODING, "magic"));
        }
    };

    @Test
    void blocklistedHeadersAndTrailersMustBeFiltered() {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/headers_and_trailers").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().names()).doesNotContain(HttpHeaderNames.SCHEME,
                                                         HttpHeaderNames.METHOD,
                                                         HttpHeaderNames.PATH,
                                                         HttpHeaderNames.TRANSFER_ENCODING);
        assertThat(res.trailers().names()).doesNotContain(HttpHeaderNames.SCHEME,
                                                          HttpHeaderNames.STATUS,
                                                          HttpHeaderNames.METHOD,
                                                          HttpHeaderNames.PATH,
                                                          HttpHeaderNames.TRANSFER_ENCODING);
    }

    @Test
    void blocklistedHeadersAndTrailersMustBeFilteredWhenMerged() {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/headers_merged").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(res.headers().names()).doesNotContain(HttpHeaderNames.SCHEME,
                                                         HttpHeaderNames.METHOD,
                                                         HttpHeaderNames.PATH,
                                                         HttpHeaderNames.TRANSFER_ENCODING);
        assertThat(res.trailers()).isEqualTo(HttpHeaders.builder()
                                                        .endOfStream(true)
                                                        .add("foo", "bar")
                                                        .build());
    }

    @Test
    void informationalHeadersDoNotContainAdditionalHeaders() {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/informational").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        final List<ResponseHeaders> informationals = res.informationals();
        assertThat(informationals).hasSize(1);
        final ResponseHeaders informationalHeaders = informationals.get(0);
        assertThat(informationalHeaders.status()).isEqualTo(HttpStatus.CONTINUE);
        assertThat(informationalHeaders.names()).doesNotContain(HttpHeaderNames.of("foo"));
        assertThat(res.headers().names()).contains(HttpHeaderNames.of("foo"));
    }

    @Test
    void responseHeadersContainsAdditionalHeaders() {
        final WebClient client = WebClient.of(server.httpUri());
        client.get("/informational").aggregate().join();
        await().until(() -> logHolder.get() != null);
        assertThat(logHolder.get().responseHeaders().names()).contains(HttpHeaderNames.of("foo"));
    }
}
