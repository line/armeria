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
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.concurrent.EventExecutor;

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
            // Echoes the concatenated body plus the received trailer, failing the first attempt so the
            // multi-chunk body is reproduced across a retry.
            sb.service("/multi", (ctx, req) -> HttpResponse.of(
                    req.aggregate().thenApply(agg -> {
                        final int hit = serverHits.incrementAndGet();
                        if (hit == 1) {
                            return AggregatedHttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR)
                                                         .toHttpResponse();
                        }
                        final String trailer = agg.trailers().get("x-trailer", "<none>");
                        return AggregatedHttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                                         agg.contentUtf8() + '|' + trailer)
                                                     .toHttpResponse();
                    })));
            // 303 See Other: method rewritten to GET and body dropped.
            sb.service("/see-other", (ctx, req) -> HttpResponse.of(
                    ResponseHeaders.of(HttpStatus.SEE_OTHER, HttpHeaderNames.LOCATION, "/target")));
            sb.service("/target", (ctx, req) -> HttpResponse.of(
                    req.aggregate().thenApply(agg -> AggregatedHttpResponse.of(
                            HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                            req.method() + ":" + agg.contentUtf8()).toHttpResponse())));
            // Plain echo that always succeeds, for exercising the request without any retry/redirect
            // decorator (the direct-consume path) and for a base-path-prefixed client.
            sb.service("/echo", (ctx, req) -> HttpResponse.of(
                    req.aggregate().thenApply(agg -> AggregatedHttpResponse.of(
                            HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                            agg.contentUtf8()).toHttpResponse())));
            // Same fail-once-then-echo behavior as /upload, reached only when a base-URI path prefix
            // ("/api") rewrites the request path — the scenario that must keep the reproducible body.
            sb.service("/api/upload", (ctx, req) -> HttpResponse.of(
                    req.aggregate().thenApply(agg -> {
                        final int hit = serverHits.incrementAndGet();
                        if (hit == 1) {
                            return AggregatedHttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR)
                                                         .toHttpResponse();
                        }
                        return AggregatedHttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                                         agg.contentUtf8()).toHttpResponse();
                    })));
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
        // The server fails exactly once, so the attempt count is deterministic: initial + one retry.
        // Assert exactly 2 so a double-subscription bug that regenerates the body 3+ times (leaking a
        // fresh factory resource per attempt) is caught rather than masked by a >= assertion.
        assertThat(serverHits).hasValue(2);
        // Body regenerated for the initial attempt and the retry.
        assertThat(bodyCalls).hasValue(2);
    }

    @Test
    void retryReproducesMultiChunkBodyAndTrailers() {
        final AtomicInteger bodyCalls = new AtomicInteger();
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/multi",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        // A genuinely multi-chunk body terminated by a trailer, the shape a streaming upload takes.
        // Each attempt must reproduce every interior chunk in order plus the trailer.
        final Supplier<StreamMessage<? extends HttpObject>> bodyFactory = () -> {
            bodyCalls.incrementAndGet();
            return StreamMessage.of(HttpData.ofUtf8("a"),
                                    HttpData.ofUtf8("b"),
                                    HttpData.ofUtf8("c"),
                                    HttpHeaders.of("x-trailer", "v"));
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
        // Concatenated interior chunks (in order) plus the reproduced trailer, on the re-sent attempt.
        assertThat(res.contentUtf8()).isEqualTo("abc|v");
        // Deterministic: initial + one retry. Exact assertion guards against over-regeneration.
        assertThat(serverHits).hasValue(2);
        assertThat(bodyCalls).hasValue(2);
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

        // The surfaced failure must carry the factory's own exception, not merely be "some Exception":
        // a connection error, timeout, or unrelated bug would also satisfy isInstanceOf(Exception.class).
        assertThatThrownBy(() -> client.execute(HttpRequest.reproducible(headers, bodyFactory),
                                                streamingOptions())
                                       .aggregate().join())
                .getRootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot regenerate body");
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
        // Deterministic: initial request + one redirect hop (no server error, so no retry). Exact
        // assertion guards against over-regeneration.
        assertThat(bodyCalls).hasValue(2);
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
        // Deterministic: initial request + one redirect hop (no server error, so no retry). Exact
        // assertion guards against over-regeneration.
        assertThat(bodyCalls).hasValue(2);
    }

    @Test
    void directConsumeWithoutDecoratorSendsBodyOnce() {
        // No retry/redirect decorator, so the request is consumed directly via its lazyBody delegate
        // (never through toDuplicator). This path is otherwise unexercised — every other test drives a
        // decorator. The factory must be invoked exactly once and the body delivered intact.
        final AtomicInteger bodyCalls = new AtomicInteger();
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/echo",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        final Supplier<StreamMessage<? extends HttpObject>> bodyFactory = () -> {
            bodyCalls.incrementAndGet();
            return StreamMessage.of(HttpData.ofUtf8("direct-body"));
        };

        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res =
                client.execute(HttpRequest.reproducible(headers, bodyFactory), streamingOptions())
                      .aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("direct-body");
        assertThat(bodyCalls).hasValue(1);
    }

    @Test
    void directConsumeSurfacesThrowingFactory() {
        // On the direct path, a throwing factory must surface its own cause to the subscriber rather
        // than hang or swallow the error.
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/echo",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        final Supplier<StreamMessage<? extends HttpObject>> bodyFactory = () -> {
            throw new IllegalStateException("cannot produce body");
        };

        final WebClient client = WebClient.of(server.httpUri());
        assertThatThrownBy(() -> client.execute(HttpRequest.reproducible(headers, bodyFactory),
                                                streamingOptions())
                                       .aggregate().join())
                .getRootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot produce body");
    }

    @Test
    void directConsumeSurfacesNullFactory() {
        // On the direct path, a factory returning null must surface an NPE, matching the duplicator
        // path's null handling.
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/echo",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        final Supplier<StreamMessage<? extends HttpObject>> bodyFactory = () -> null;

        final WebClient client = WebClient.of(server.httpUri());
        assertThatThrownBy(() -> client.execute(HttpRequest.reproducible(headers, bodyFactory),
                                                streamingOptions())
                                       .aggregate().join())
                .getRootCause()
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toDuplicatorIgnoresMaxRequestLength() {
        // The reproducible duplicator never buffers, so it must ignore the maxRequestLength cap that a
        // buffering DefaultStreamMessageDuplicator would enforce. A body far larger than a tiny cap must
        // still stream to completion; a regression that fell back to a buffering duplicator would throw
        // ContentTooLargeException here.
        final byte[] large = new byte[64 * 1024];
        final Supplier<StreamMessage<? extends HttpObject>> bodyFactory =
                () -> StreamMessage.of(HttpData.wrap(large));
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/echo");

        final EventExecutor executor = CommonPools.workerGroup().next();
        final HttpRequestDuplicator duplicator =
                HttpRequest.reproducible(headers, bodyFactory).toDuplicator(executor, 8);

        final AggregatedHttpRequest produced = duplicator.duplicate().aggregate().join();
        assertThat(produced.content().length()).isEqualTo(large.length);
        duplicator.close();
    }

    @Test
    void basePathPrefixRemainsReproducible() {
        // A WebClient built with a base-URI path prefix rewrites the request path via
        // req.withHeaders(...). If ReproducibleHttpRequest did not override withHeaders, the rewritten
        // request would be a plain HeaderOverridingHttpRequest whose toDuplicator falls back to the
        // buffering DefaultStreamMessageDuplicator — silently reintroducing the ~2 GiB limit. This test
        // pins that the rewritten request still regenerates its body per attempt (non-buffering path).
        final AtomicInteger bodyCalls = new AtomicInteger();
        // Header path is "/upload"; the base URI prefix "/api" makes the effective path "/api/upload",
        // forcing a path rewrite. Route the server so /api/upload retries once like /upload does.
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/upload",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        final Supplier<StreamMessage<? extends HttpObject>> bodyFactory = () -> {
            bodyCalls.incrementAndGet();
            return StreamMessage.of(HttpData.ofUtf8("prefixed-body"));
        };

        final WebClient client =
                WebClient.builder(server.httpUri() + "/api")
                         .decorator(RetryingClient.newDecorator(
                                 RetryRule.builder().onServerErrorStatus().thenBackoff()))
                         .build();

        final AggregatedHttpResponse res =
                client.execute(HttpRequest.reproducible(headers, bodyFactory), streamingOptions())
                      .aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("prefixed-body");
        // Regenerated for the initial attempt and the retry — proving the path-rewritten request kept
        // the reproducible (non-buffering) duplicator rather than falling back to buffering.
        assertThat(bodyCalls).hasValue(2);
    }
}
