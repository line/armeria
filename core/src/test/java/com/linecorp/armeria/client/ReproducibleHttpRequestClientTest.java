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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ReproducibleHttpRequestClientTest {

    private static final AtomicInteger serverHits = new AtomicInteger();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/upload", (ctx, req) -> HttpResponse.of(
                    req.aggregate().thenApply(agg -> {
                        final int hit = serverHits.incrementAndGet();
                        if (hit == 1) {
                            // Fail the first attempt to trigger a retry.
                            return AggregatedHttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR)
                                                         .toHttpResponse();
                        }
                        return AggregatedHttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                                         agg.contentUtf8()).toHttpResponse();
                    })));
            // Redirect chain: /first -> /second, echoing the body at /second.
            sb.service("/first", (ctx, req) -> HttpResponse.of(
                    ResponseHeaders.of(HttpStatus.TEMPORARY_REDIRECT,
                                       HttpHeaderNames.LOCATION, "/second")));
            sb.service("/second", (ctx, req) -> HttpResponse.of(
                    req.aggregate().thenApply(agg -> AggregatedHttpResponse.of(
                            HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                            agg.contentUtf8()).toHttpResponse())));
            // 303 See Other: method rewritten to GET and body dropped.
            sb.service("/see-other", (ctx, req) -> HttpResponse.of(
                    ResponseHeaders.of(HttpStatus.SEE_OTHER, HttpHeaderNames.LOCATION, "/target")));
            sb.service("/target", (ctx, req) -> HttpResponse.of(
                    req.aggregate().thenApply(agg -> AggregatedHttpResponse.of(
                            HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                            req.method() + ":" + agg.contentUtf8()).toHttpResponse())));
        }
    };

    @BeforeEach
    void resetServerHits() {
        serverHits.set(0);
    }

    private static RequestOptions streamingOptions() {
        return RequestOptions.builder()
                             .exchangeType(ExchangeType.REQUEST_STREAMING)
                             .build();
    }

    @Test
    void factoryNotInvokedEagerly() {
        final AtomicInteger bodyCalls = new AtomicInteger();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/upload");
        final Supplier<StreamMessage<? extends HttpObject>> bodyFactory = () -> {
            bodyCalls.incrementAndGet();
            return StreamMessage.of(HttpData.ofUtf8("hello-body"));
        };

        // Creating the request must not call the factory; it is invoked lazily per attempt only.
        final HttpRequest req = HttpRequest.reproducible(headers, bodyFactory);
        assertThat(bodyCalls).hasValue(0);
        req.abort();
    }

    @Test
    void retryRegeneratesBody() {
        final AtomicInteger bodyCalls = new AtomicInteger();
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/upload",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        final Supplier<StreamMessage<? extends HttpObject>> bodyFactory = () -> {
            bodyCalls.incrementAndGet();
            return StreamMessage.of(HttpData.ofUtf8("hello-body"));
        };

        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(RetryingClient.newDecorator(
                                 RetryRule.builder().onServerErrorStatus().thenBackoff()))
                         .build();

        final AggregatedHttpResponse res =
                client.execute(HttpRequest.reproducible(headers, bodyFactory), streamingOptions())
                      .aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("hello-body");
        assertThat(serverHits).hasValueGreaterThanOrEqualTo(2);
        // Body regenerated for the initial attempt and the retry.
        assertThat(bodyCalls).hasValueGreaterThanOrEqualTo(2);
    }

    @Test
    void factoryThrowingOnRetryFailsFast() {
        final AtomicInteger bodyCalls = new AtomicInteger();
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/upload",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        // Initial body succeeds; the factory throws when asked to regenerate it for the retry.
        final Supplier<StreamMessage<? extends HttpObject>> bodyFactory = () -> {
            if (bodyCalls.getAndIncrement() == 0) {
                return StreamMessage.of(HttpData.ofUtf8("hello-body"));
            }
            throw new IllegalStateException("cannot regenerate body");
        };

        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(RetryingClient.newDecorator(
                                 RetryRule.builder().onServerErrorStatus().thenBackoff()))
                         .build();

        assertThatThrownBy(() -> client.execute(HttpRequest.reproducible(headers, bodyFactory),
                                                streamingOptions())
                                       .aggregate().join())
                .isInstanceOf(Exception.class);
        // The factory is consulted twice: once for the initial body (via reproducible()), once for the
        // failing retry. It fails fast rather than looping through the whole retry budget.
        assertThat(bodyCalls).hasValue(2);
    }

    @Test
    void followsRedirectRegeneratingBody() {
        final AtomicInteger bodyCalls = new AtomicInteger();
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/first",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        final Supplier<StreamMessage<? extends HttpObject>> bodyFactory = () -> {
            bodyCalls.incrementAndGet();
            return StreamMessage.of(HttpData.ofUtf8("redir-body"));
        };

        final WebClient client =
                WebClient.builder(server.httpUri())
                         .followRedirects()
                         .build();

        final AggregatedHttpResponse res =
                client.execute(HttpRequest.reproducible(headers, bodyFactory), streamingOptions())
                      .aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("redir-body");
        // Body regenerated for the initial request and the redirected hop.
        assertThat(bodyCalls).hasValueGreaterThanOrEqualTo(2);
    }

    @Test
    void seeOtherRedirectDropsBody() {
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/see-other",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        final Supplier<StreamMessage<? extends HttpObject>> bodyFactory =
                () -> StreamMessage.of(HttpData.ofUtf8("see-other-body"));

        final WebClient client =
                WebClient.builder(server.httpUri())
                         .followRedirects()
                         .build();

        final AggregatedHttpResponse res =
                client.execute(HttpRequest.reproducible(headers, bodyFactory), streamingOptions())
                      .aggregate().join();

        // On a 303 the method is rewritten to GET and the body dropped; the duplicator is aborted
        // and must not throw.
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("GET:");
    }

    @Test
    void stackedRetryAndRedirect() {
        final AtomicInteger bodyCalls = new AtomicInteger();
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/first",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        final Supplier<StreamMessage<? extends HttpObject>> bodyFactory = () -> {
            bodyCalls.incrementAndGet();
            return StreamMessage.of(HttpData.ofUtf8("redir-body"));
        };

        // Both RetryingClient and RedirectingClient present; the reproducible body must be resent
        // correctly through the redirect without a destructive double-consume.
        final WebClient client =
                WebClient.builder(server.httpUri())
                         .followRedirects()
                         .decorator(RetryingClient.newDecorator(
                                 RetryRule.builder().onServerErrorStatus().thenBackoff()))
                         .build();

        final AggregatedHttpResponse res =
                client.execute(HttpRequest.reproducible(headers, bodyFactory), streamingOptions())
                      .aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("redir-body");
        // Body regenerated for the initial request and the redirected hop.
        assertThat(bodyCalls).hasValueGreaterThanOrEqualTo(2);
    }
}
