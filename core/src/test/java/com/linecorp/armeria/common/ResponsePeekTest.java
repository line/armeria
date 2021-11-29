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
    void peekHeadersWithMapChain() {
        final HttpResponse res = HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                                                 HttpData.ofUtf8("foo"),
                                                 HttpHeaders.of("status", "0"));

        final AggregatedHttpResponse aggregated =
                res.mapHeaders(headers -> headers.toBuilder().add("header1", "1").build())
                   .mapHeaders(headers -> headers.toBuilder().add("header2", "2").build())
                   .peekHeaders(headers -> {
                       assertThat(headers.get("header1")).isEqualTo("1");
                       assertThat(headers.get("header2")).isEqualTo("2");
                   })
                   .peekHeaders(headers -> peekCount.incrementAndGet())
                   .mapData(data -> HttpData.ofUtf8(data.toStringUtf8() + '!'))
                   .mapData(data -> HttpData.ofUtf8(data.toStringUtf8() + '!'))
                   .peekHeaders(headers -> peekCount.incrementAndGet())
                   .mapTrailers(trailers -> trailers.toBuilder().add("trailer1", "1").build())
                   .mapTrailers(trailers -> trailers.toBuilder().add("trailer2", "2").build())
                   .peekHeaders(headers -> peekCount.incrementAndGet())
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
                .isInstanceOfSatisfying(CompletionException.class, t ->
                        assertThat(t.getCause()).isInstanceOf(IllegalStateException.class));
        assertThat(peekCount).hasValue(0);
    }
}
