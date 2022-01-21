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

public class RequestPeekTest {
    private static final AtomicInteger peekCount = new AtomicInteger();

    @BeforeEach
    void clear() {
        peekCount.set(0);
    }

    @Test
    void peekData() {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/test"),
                                               HttpData.ofUtf8("foo"));
        final HttpRequest transformed = req
                .peekData(data -> assertThat(data.toStringUtf8()).doesNotContain(" bar"))
                .mapData(data -> HttpData.ofUtf8(data.toStringUtf8() + " bar"))
                .peekData(data -> assertThat(data.toStringUtf8()).contains(" bar"));

        assertThat(transformed.aggregate().join().contentUtf8()).isEqualTo("foo bar");
        assertThat(transformed.headers()).isEqualTo(req.headers());
    }

    @Test
    void peekTrailers() {
        final HttpRequest noTrailers = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/test"),
                                                      HttpData.ofUtf8("foo"));
        final AtomicBoolean invoked = new AtomicBoolean();
        final HttpRequest transformed1 = noTrailers
                .peekTrailers(trailers -> peekCount.incrementAndGet())
                .mapTrailers(trailers -> {
                    invoked.set(true);
                    return trailers;
                });

        final AggregatedHttpRequest aggregated1 = transformed1.aggregate().join();
        assertThat(invoked).isFalse();
        assertThat(peekCount).hasValue(0);
        assertThat(transformed1.headers()).isEqualTo(noTrailers.headers());
        assertThat(aggregated1.contentUtf8()).isEqualTo("foo");
        assertThat(aggregated1.trailers().isEmpty()).isTrue();

        final HttpRequest withTrailers = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/test"),
                                                        HttpData.ofUtf8("foo"),
                                                        HttpHeaders.of("trailer1", "1"));

        final HttpRequest transformed2 = withTrailers
                .peekTrailers(trailers -> {
                    assertThat(trailers.get("trailer1")).isEqualTo("1");
                    assertThat(trailers.get("trailer2")).isNull();
                })
                .mapTrailers(trailers -> {
                    invoked.set(true);
                    if ("1".equals(trailers.get("trailer1"))) {
                        return trailers.toBuilder().add("trailer2", "2").build();
                    }
                    return trailers;
                })
                .peekTrailers(trailers -> {
                    assertThat(trailers.get("trailer1")).isEqualTo("1");
                    assertThat(trailers.get("trailer2")).isEqualTo("2");
                })
                .peekTrailers(trailers -> peekCount.incrementAndGet());

        final AggregatedHttpRequest aggregated2 = transformed2.aggregate().join();
        assertThat(invoked).isTrue();
        assertThat(peekCount).hasValue(1);
        assertThat(aggregated2.contentUtf8()).isEqualTo("foo");
        assertThat(aggregated2.trailers()).hasSize(2);
        assertThat(aggregated2.trailers().get("trailer1")).isEqualTo("1");
        assertThat(aggregated2.trailers().get("trailer2")).isEqualTo("2");
    }

    @Test
    void peekChain() {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/test"),
                                               HttpData.ofUtf8("foo"),
                                               HttpHeaders.of("status", "0"));
        final AggregatedHttpRequest response =
                req.mapHeaders(headers -> headers.toBuilder().add("header1", "1").build())
                   .mapHeaders(headers -> headers.toBuilder().add("header2", "2").build())
                   .peekData(data -> assertThat(data.toStringUtf8()).isEqualTo("foo"))
                   .mapData(data -> HttpData.ofUtf8(data.toStringUtf8() + '!'))
                   .mapData(data -> HttpData.ofUtf8(data.toStringUtf8() + '!'))
                   .peekData(data -> assertThat(data.toStringUtf8()).isEqualTo("foo!!"))
                   .peekData(trailers -> peekCount.incrementAndGet())
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

        assertThat(response.headers().get("header1")).isEqualTo("1");
        assertThat(response.headers().get("header2")).isEqualTo("2");
        assertThat(response.contentUtf8()).isEqualTo("foo!!");
        assertThat(response.trailers().get("status")).isEqualTo("0");
        assertThat(response.trailers().get("trailer1")).isEqualTo("1");
        assertThat(response.trailers().get("trailer2")).isEqualTo("2");
        assertThat(peekCount).hasValue(2);
    }

    @Test
    void peekError() {
        final IllegalStateException first = new IllegalStateException("1");
        final IllegalStateException second = new IllegalStateException("2");
        final HttpRequestWriter requestWriter = HttpRequest.streaming(HttpMethod.GET, "/foo");
        requestWriter.write(HttpData.ofUtf8("body"));
        requestWriter.close(first);
        final HttpRequest transformed = requestWriter
                .peekError(error -> assertThat(error).isSameAs(first))
                .mapError(error -> second);
        assertThatThrownBy(() -> transformed.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(second);
    }
}
