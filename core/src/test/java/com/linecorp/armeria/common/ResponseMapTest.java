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

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class ResponseMapTest {

    @Test
    void mapHeaders() {
        final HttpResponse res = HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, "foo");

        final HttpResponse transformed = res.mapHeaders(headers -> {
            return headers.withMutations(builder -> builder.add(HttpHeaderNames.USER_AGENT, "Armeria"));
        });

        final AggregatedHttpResponse aggregated = transformed.aggregate().join();
        assertThat(aggregated.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregated.headers().get(HttpHeaderNames.USER_AGENT)).isEqualTo("Armeria");
        assertThat(aggregated.contentUtf8()).isEqualTo("foo");
    }

    @Test
    void mapData() {
        final HttpResponse res = HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                                                 HttpData.ofUtf8("foo"),
                                                 HttpData.ofUtf8("bar"));
        final HttpResponse transformed = res.mapData(data -> HttpData.ofUtf8(data.toStringUtf8() + '\n'));

        final AggregatedHttpResponse aggregated = transformed.aggregate().join();
        assertThat(aggregated.contentUtf8()).isEqualTo("foo\nbar\n");
        assertThat(aggregated.headers()).isEqualTo(res.aggregate().join().headers());
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
}
