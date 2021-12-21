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

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResponsePeekTest {
    private static final AtomicInteger peekCount = new AtomicInteger();

    @BeforeEach
    void clear() {
        peekCount.set(0);
    }

    @Test
    void peekHeaders() {
        final HttpResponse res = HttpResponse.of(ResponseHeaders.of(HttpStatus.OK), HttpData.ofUtf8("foo"));

        final HttpResponse response = res
                .peekHeaders(headers -> assertThat(headers.status()).isEqualTo(HttpStatus.OK))
                .peekHeaders(headers -> peekCount.incrementAndGet());

        final AggregatedHttpResponse aggregated = response.aggregate().join();
        assertThat(aggregated.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregated.contentUtf8()).isEqualTo("foo");
        assertThat(peekCount).hasValue(1);
    }

    @Test
    void peekHeadersThrow() {
        final HttpResponse res = HttpResponse.of(ResponseHeaders.of(HttpStatus.OK), HttpData.ofUtf8("foo"));

        assertThatThrownBy(() -> res
                .peekHeaders(headers -> {
                    if (HttpStatus.OK.equals(headers.status())) {
                        throw new IllegalStateException();
                    }
                })
                .peekHeaders(headers -> peekCount.incrementAndGet())
                .aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(peekCount).hasValue(0);
    }

    @Test
    void peekData() {
        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK);
        final HttpResponse res = HttpResponse.of(headers,
                                                 HttpData.ofUtf8("foo"),
                                                 HttpData.ofUtf8("bar"));
        final HttpResponse transformed = res
                .peekData(data -> assertThat(data.toStringUtf8()).doesNotContain("\n"))
                .mapData(data -> HttpData.ofUtf8(data.toStringUtf8() + '\n'))
                .peekData(data -> assertThat(data.toStringUtf8()).contains("\n"));

        final AggregatedHttpResponse aggregated = transformed.aggregate().join();
        assertThat(aggregated.contentUtf8()).isEqualTo("foo\nbar\n");
    }

    @Test
    void peekTrailers() {
        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK);
        final HttpResponse noTrailers = HttpResponse.of(headers, HttpData.ofUtf8("foo"));
        final AtomicBoolean invoked = new AtomicBoolean();
        final HttpResponse transformed1 = noTrailers
                .peekTrailers(trailers -> peekCount.incrementAndGet())
                .mapTrailers(trailers -> {
                    invoked.set(true);
                    return trailers;
                });

        final AggregatedHttpResponse aggregated1 = transformed1.aggregate().join();
        assertThat(invoked).isFalse();
        assertThat(peekCount).hasValue(0);
        assertThat(aggregated1.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregated1.contentUtf8()).isEqualTo("foo");
        assertThat(aggregated1.trailers().isEmpty()).isTrue();

        final HttpResponse withTrailers = HttpResponse.of(headers,
                                                          HttpData.ofUtf8("foo"),
                                                          HttpHeaders.of("status", "13"));

        final HttpResponse transformed2 = withTrailers
                .peekTrailers(trailers -> {
                    assertThat(trailers.get("ok")).isNull();
                    assertThat(trailers.get("error")).isNull();
                })
                .mapTrailers(trailers -> {
                    invoked.set(true);
                    if ("13".equals(trailers.get("status"))) {
                        return trailers.toBuilder().add("error", "INTERNAL").build();
                    } else {
                        return trailers.toBuilder().add("ok", "true").build();
                    }
                })
                .peekTrailers(trailers -> {
                    assertThat(trailers.get("ok")).isNull();
                    assertThat(trailers.get("error")).isEqualTo("INTERNAL");
                })
                .peekTrailers(trailers -> peekCount.incrementAndGet());

        final AggregatedHttpResponse aggregated2 = transformed2.aggregate().join();
        assertThat(invoked).isTrue();
        assertThat(peekCount).hasValue(1);
        assertThat(aggregated2.headers().get("ok")).isNull();
        assertThat(aggregated2.headers().get("error")).isNull();
        assertThat(aggregated2.contentUtf8()).isEqualTo("foo");
        assertThat(aggregated2.trailers().size()).isEqualTo(2);
        assertThat(aggregated2.trailers().get("status")).isEqualTo("13");
        assertThat(aggregated2.trailers().get("error")).isEqualTo("INTERNAL");
        assertThat(aggregated2.trailers().get("ok")).isNull();
    }

    @Test
    void peekChain() {
        final HttpResponse res = HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                                                 HttpData.ofUtf8("foo"),
                                                 HttpHeaders.of("status", "0"));

        final AggregatedHttpResponse aggregated =
                res.peekHeaders(headers -> {
                    assertThat(headers.get("header1")).isNull();
                    assertThat(headers.get("header2")).isNull();
                })
                   .mapHeaders(headers -> headers.toBuilder().add("header1", "1").build())
                   .mapHeaders(headers -> headers.toBuilder().add("header2", "2").build())
                   .peekHeaders(headers -> {
                       assertThat(headers.get("header1")).isEqualTo("1");
                       assertThat(headers.get("header2")).isEqualTo("2");
                   })
                   .peekHeaders(headers -> peekCount.incrementAndGet())
                   .peekData(data -> assertThat(data.toStringUtf8()).isEqualTo("foo"))
                   .mapData(data -> HttpData.ofUtf8(data.toStringUtf8() + '!'))
                   .mapData(data -> HttpData.ofUtf8(data.toStringUtf8() + '!'))
                   .peekData(data -> assertThat(data.toStringUtf8()).isEqualTo("foo!!"))
                   .peekData(data -> peekCount.incrementAndGet())
                   .peekTrailers(trailers -> {
                       assertThat(trailers.get("trailer1")).isNull();
                       assertThat(trailers.get("trailer2")).isNull();
                   })
                   .mapTrailers(trailers -> trailers.toBuilder().add("trailer1", "1").build())
                   .mapTrailers(trailers -> trailers.toBuilder().add("trailer2", "2").build())
                   .peekTrailers(trailers -> {
                       assertThat(trailers.get("trailer1")).isEqualTo("1");
                       assertThat(trailers.get("trailer2")).isEqualTo("2");
                   })
                   .peekTrailers(trailers -> peekCount.incrementAndGet())
                   .aggregate().join();

        assertThat(aggregated.headers().get("header1")).isEqualTo("1");
        assertThat(aggregated.headers().get("header2")).isEqualTo("2");
        assertThat(aggregated.contentUtf8()).isEqualTo("foo!!");
        assertThat(aggregated.trailers().get("status")).isEqualTo("0");
        assertThat(aggregated.trailers().get("trailer1")).isEqualTo("1");
        assertThat(aggregated.trailers().get("trailer2")).isEqualTo("2");
        assertThat(peekCount).hasValue(3);
    }

    @Test
    void peekError() {
        final IllegalStateException first = new IllegalStateException("1");
        final IllegalStateException second = new IllegalStateException("2");
        final HttpResponse failed = HttpResponse.ofFailure(first);
        final HttpResponse transformed = failed
                .peekError(error -> assertThat(error).isSameAs(first))
                .mapError(error -> second);
        assertThatThrownBy(() -> transformed.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(second);
    }
}
