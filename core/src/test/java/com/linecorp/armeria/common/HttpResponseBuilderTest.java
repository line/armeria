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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.stream.StreamMessage;

class HttpResponseBuilderTest {

    @Test
    void status() {
        HttpResponse res = HttpResponse.builder()
                                       .status(200)
                                       .build();
        AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.content()).isEqualTo(HttpData.empty());
        assertThat(aggregatedRes.trailers()).isEqualTo(HttpHeaders.of());

        res = HttpResponse.builder()
                          .status(HttpStatus.NOT_FOUND)
                          .build();
        aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(aggregatedRes.content()).isEqualTo(HttpData.empty());
        assertThat(aggregatedRes.trailers()).isEqualTo(HttpHeaders.of());

        assertThatThrownBy(() -> HttpResponse.builder()
                                             .status(null)
                                             .build()
        ).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ok() {
        final HttpResponse res = HttpResponse.builder()
                                             .ok()
                                             .build();
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.content()).isEqualTo(HttpData.empty());
        assertThat(aggregatedRes.trailers()).isEqualTo(HttpHeaders.of());
    }

    @Test
    void badRequest() {
        final HttpResponse res = HttpResponse.builder()
                                             .badRequest()
                                             .build();
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(aggregatedRes.content()).isEqualTo(HttpData.empty());
        assertThat(aggregatedRes.trailers()).isEqualTo(HttpHeaders.of());
    }

    @Test
    void internalServerError() {
        final HttpResponse res = HttpResponse.builder()
                                             .internalServerError()
                                             .build();
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(aggregatedRes.content()).isEqualTo(HttpData.empty());
        assertThat(aggregatedRes.trailers()).isEqualTo(HttpHeaders.of());
    }

    @Test
    void buildWithoutStatus() {
        assertThatThrownBy(() -> HttpResponse.builder()
                                             .content("Armeriaはいろんな使い方がアルメリア")
                                             .build()
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void buildWithPlainContent() {
        // Using non-ascii to test UTF-8 conversion
        final HttpResponse res = HttpResponse.builder()
                                             .ok()
                                             .content("Armeriaはいろんな使い方がアルメリア")
                                             .build();
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.contentUtf8()).isEqualTo("Armeriaはいろんな使い方がアルメリア");
        assertThat(aggregatedRes.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    }

    @Test
    void buildWithPlainFormat() {
        // Using non-ascii to test UTF-8 conversion
        final HttpResponse res = HttpResponse.builder()
                                             .ok()
                                             .content("%sはいろんな使い方が%s",
                                                      "Armeria", "アルメリア")
                                             .build();
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.contentUtf8()).isEqualTo("Armeriaはいろんな使い方がアルメリア");
        assertThat(aggregatedRes.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    }

    @Test
    void buildWithContentTypeAndPlainContent() {
        final HttpResponse res = HttpResponse.builder()
                                             .ok()
                                             .content(MediaType.PLAIN_TEXT_UTF_8,
                                                      "Armeriaはいろんな使い方がアルメリア")
                                             .build();
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.contentUtf8()).isEqualTo("Armeriaはいろんな使い方がアルメリア");
        assertThat(aggregatedRes.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    }

    @Test
    void buildWithContentTypeAndPlainFormat() {
        final HttpResponse res = HttpResponse.builder()
                                             .ok()
                                             .content(MediaType.PLAIN_TEXT_UTF_8,
                                                      "%sはいろんな使い方が%s",
                                                      "Armeria", "アルメリア")
                                             .build();
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.contentUtf8()).isEqualTo("Armeriaはいろんな使い方がアルメリア");
        assertThat(aggregatedRes.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    }

    @Test
    void buildWithByteContent() {
        final HttpResponse res = HttpResponse.builder()
                                             .ok()
                                             .content(MediaType.PLAIN_TEXT_UTF_8,
                                                      "Armeriaはいろんな使い方がアルメリア"
                                                              .getBytes(StandardCharsets.UTF_8))
                                             .build();
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.contentUtf8()).isEqualTo("Armeriaはいろんな使い方がアルメリア");
        assertThat(aggregatedRes.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    }

    @Test
    void buildWithPublisherContent() {
        final HttpResponse res = HttpResponse.builder()
                                             .ok()
                                             .content(MediaType.PLAIN_TEXT_UTF_8,
                                                      StreamMessage.of(
                                                              HttpData.ofUtf8(
                                                                      "Armeriaはいろんな使い方がアルメリア"
                                                              )
                                                      ))
                                             .build();
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.contentUtf8()).isEqualTo("Armeriaはいろんな使い方がアルメリア");
        assertThat(aggregatedRes.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    }

    @Test
    void buildWithContentJson() {
        final HttpResponse res = HttpResponse.builder()
                                             .ok()
                                             .contentJson(new SampleObject(15, "Armeria"))
                                             .build();
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        // language=JSON
        assertThat(aggregatedRes.contentUtf8()).isEqualTo("{\"id\":15,\"name\":\"Armeria\"}");
        assertThat(aggregatedRes.contentType()).isEqualTo(MediaType.JSON);
    }

    @Test
    void buildWithHeader() {
        final HttpResponse res = HttpResponse.builder()
                                             .ok()
                                             .header("some-header", "test-value")
                                             .build();
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.headers().contains("some-header")).isTrue();
        assertThat(aggregatedRes.headers().get("some-header")).isEqualTo("test-value");
    }

    @Test
    void buildWithHeaders() {
        final HttpResponse res = HttpResponse.builder()
                                             .ok()
                                             .headers(HttpHeaders.of("header-1",
                                                                     "test-value1",
                                                                     "header-2",
                                                                     "test-value2"))
                                             .build();
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.headers().contains("header-1")).isTrue();
        assertThat(aggregatedRes.headers().contains("header-2")).isTrue();
        assertThat(aggregatedRes.headers().get("header-1")).isEqualTo("test-value1");
        assertThat(aggregatedRes.headers().get("header-2")).isEqualTo("test-value2");
    }

    @Test
    void buildWithTrailers() {
        final HttpResponse res = HttpResponse.builder()
                                             .ok()
                                             .trailers(HttpHeaders.of("trailer-name",
                                                                      "trailer-value"))
                                             .build();
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.trailers().contains("trailer-name")).isTrue();
        assertThat(aggregatedRes.trailers().get("trailer-name")).isEqualTo("trailer-value");
    }

    @Test
    void buildComplex() {
        final HttpResponse res = HttpResponse.builder()
                                             .ok()
                                             .headers(HttpHeaders.of("header-1",
                                                                     "test-value1",
                                                                     "header-2",
                                                                     "test-value2"))
                                             .header(HttpHeaderNames.ACCEPT_ENCODING, "gzip")
                                             .header(HttpHeaderNames.ACCEPT_ENCODING, "deflate")
                                             .header(HttpHeaderNames.ACCEPT_ENCODING, "gzip")
                                             .content("Armeriaはいろんな使い方がアルメリア")
                                             .trailers(HttpHeaders.of("trailer-name",
                                                                      "trailer-value"))
                                             .build();
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.headers().contains("header-1")).isTrue();
        assertThat(aggregatedRes.headers().contains("header-2")).isTrue();
        assertThat(aggregatedRes.headers().get("header-1")).isEqualTo("test-value1");
        assertThat(aggregatedRes.headers().get("header-2")).isEqualTo("test-value2");
        assertThat(aggregatedRes.headers().getAll(HttpHeaderNames.ACCEPT_ENCODING))
                .isEqualTo(ImmutableList.of("gzip", "deflate", "gzip"));
        assertThat(aggregatedRes.contentUtf8()).isEqualTo("Armeriaはいろんな使い方がアルメリア");
        assertThat(aggregatedRes.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(aggregatedRes.trailers().contains("trailer-name")).isTrue();
        assertThat(aggregatedRes.trailers().get("trailer-name")).isEqualTo("trailer-value");
    }

    static class SampleObject {
        private final int id;

        private final String name;

        SampleObject(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }
}
