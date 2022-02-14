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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.util.OsType;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ResponseMapTest {

    private static AtomicBoolean invokedTrailer = new AtomicBoolean();
    private static AtomicBoolean invokedInformational = new AtomicBoolean();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.serviceUnder(
                    "/500",
                    ((ctx, req) -> {
                        throw new RuntimeException("Expected");
                    }));
            sb.decorator((delegate, ctx, req) -> delegate
                    .serve(ctx, req)
                    .mapHeaders(
                            (headers) -> headers.withMutations(
                                    builder -> builder.add(HttpHeaderNames.USER_AGENT, "Armeria")
                            )
                    )
                    .mapTrailers((headers) -> {
                        invokedTrailer.set(true);
                        return headers;
                    })
                    .mapInformational((headers) -> {
                        invokedInformational.set(true);
                        return headers;
                    })
            );
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @AfterAll
    static void stopSynchronously() {
        if (SystemInfo.osType() == OsType.WINDOWS) {
            // Shut down the server completely so that no files
            // are open before deleting the temporary directory.
            server.stop().join();
        }
    }

    @BeforeEach
    void resetInvocationStatus() {
        invokedTrailer.set(false);
        invokedInformational.set(false);
    }

    @Test
    void mapHeaders() {
        HttpResponse res = HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, "foo");
        HttpResponse transformed = res.mapHeaders(headers -> {
            return headers.withMutations(builder -> builder.add(HttpHeaderNames.USER_AGENT, "Armeria"));
        });

        AggregatedHttpResponse aggregated = transformed.aggregate().join();
        assertThat(aggregated.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregated.headers().get(HttpHeaderNames.USER_AGENT)).isEqualTo("Armeria");
        assertThat(aggregated.contentUtf8()).isEqualTo("foo");

        res = HttpResponse.ofFailure(new RuntimeException("Expected"));
        transformed = res.mapHeaders(
                headers -> headers.withMutations(
                        builder -> builder.add(HttpHeaderNames.USER_AGENT, "Armeria")
                )
        );
        try {
            transformed.aggregate().join();
            failBecauseExceptionWasNotThrown(CompletionException.class);
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(HttpResponseException.class);
            final HttpResponseException ex = (HttpResponseException) e.getCause();
            aggregated = ex.httpResponse().aggregate().join();
            assertThat(aggregated.headers().status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(aggregated.headers().get(HttpHeaderNames.USER_AGENT)).isEqualTo("Armeria");
        }

        final AggregatedHttpResponse httpRes = BlockingWebClient.of(server.httpUri()).get("/500");
        assertThat(invokedInformational).isTrue();
        assertThat(invokedTrailer).isTrue();
        assertThat(httpRes.headers().get(HttpHeaderNames.USER_AGENT)).isEqualTo("Armeria");

        CompletableFuture<HttpResponse> foo = new CompletableFuture<>();
        foo.completeExceptionally(HttpResponseException.of(HttpResponse.ofRedirect("/foo")));
        res = HttpResponse.from(foo);
        transformed = res.mapHeaders(
                headers -> headers.withMutations(
                        builder -> builder.add(HttpHeaderNames.USER_AGENT, "Armeria")
                )
        );
        try {
            transformed.aggregate().join();
            failBecauseExceptionWasNotThrown(CompletionException.class);
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(HttpResponseException.class);
            final HttpResponseException ex = (HttpResponseException) e.getCause();
            aggregated = ex.httpResponse().aggregate().join();
            assertThat(aggregated.headers().status()).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
            assertThat(aggregated.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("/foo");
            // This fails because DeferredHttpResponse is not
            assertThat(aggregated.headers().get(HttpHeaderNames.USER_AGENT)).isEqualTo("Armeria");
        }
    }

    @Test
    void mapData() {
        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK);
        final HttpResponse res = HttpResponse.of(headers,
                                                 HttpData.ofUtf8("foo"),
                                                 HttpData.ofUtf8("bar"));
        final HttpResponse transformed = res.mapData(data -> HttpData.ofUtf8(data.toStringUtf8() + '\n'));

        final AggregatedHttpResponse aggregated = transformed.aggregate().join();
        assertThat(aggregated.contentUtf8()).isEqualTo("foo\nbar\n");
        assertThat(aggregated.headers().toBuilder().removeAndThen(HttpHeaderNames.CONTENT_LENGTH).build())
                .isEqualTo(headers);
    }

    @Test
    void mapTrailers() {
        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK);
        final HttpResponse noTrailers = HttpResponse.of(headers, HttpData.ofUtf8("foo"));
        final AtomicBoolean invoked = new AtomicBoolean();
        final HttpResponse transformed1 = noTrailers.mapTrailers(trailers -> {
            invoked.set(true);
            return trailers;
        });

        final AggregatedHttpResponse aggregated1 = transformed1.aggregate().join();
        assertThat(invoked).isFalse();
        assertThat(aggregated1.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregated1.contentUtf8()).isEqualTo("foo");
        assertThat(aggregated1.trailers().isEmpty()).isTrue();

        final HttpResponse withTrailers = HttpResponse.of(headers,
                                                          HttpData.ofUtf8("foo"),
                                                          HttpHeaders.of("status", "13"));

        final HttpResponse transformed2 = withTrailers.mapTrailers(trailers -> {
            invoked.set(true);
            if ("13".equals(trailers.get("status"))) {
                return trailers.toBuilder().add("error", "INTERNAL").build();
            } else {
                return trailers.toBuilder().add("ok", "true").build();
            }
        });

        final AggregatedHttpResponse aggregated2 = transformed2.aggregate().join();
        assertThat(invoked).isTrue();
        assertThat(aggregated2.headers().get("ok")).isNull();
        assertThat(aggregated2.headers().get("error")).isNull();
        assertThat(aggregated2.contentUtf8()).isEqualTo("foo");
        assertThat(aggregated2.trailers().size()).isEqualTo(2);
        assertThat(aggregated2.trailers().get("status")).isEqualTo("13");
        assertThat(aggregated2.trailers().get("error")).isEqualTo("INTERNAL");
        assertThat(aggregated2.trailers().get("ok")).isNull();
    }

    @Test
    void mapError() {
        final IllegalStateException first = new IllegalStateException("1");
        final IllegalStateException second = new IllegalStateException("2");
        final HttpResponse failed = HttpResponse.ofFailure(first);
        final HttpResponse transformed = failed.mapError(error -> {
            assertThat(error).isSameAs(first);
            return second;
        });
        assertThatThrownBy(() -> transformed.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(second);
    }
}
