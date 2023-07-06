/*
 * Copyright 2023 LINE Corporation
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

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;

import io.netty.handler.codec.http.HttpHeaderValues;

class ArmeriaHttpHeadersTest {
    @Test
    void inboundCookiesMustBeMergedForHttp1() {
        final ArmeriaHttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder()
                                                                           .method(HttpMethod.GET)
                                                                           .path("/test"));
        in.add(HttpHeaderNames.COOKIE, "a=b; c=d");
        in.add(HttpHeaderNames.COOKIE, "e=f;g=h");
        in.add(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8.toString());
        in.add(HttpHeaderNames.COOKIE, "i=j");
        in.add(HttpHeaderNames.COOKIE, "k=l;");

        assertThat(in.buildRequestHeaders().getAll(HttpHeaderNames.COOKIE))
                .containsExactly("a=b; c=d; e=f; g=h; i=j; k=l");
    }

    @Test
    void stripTEHeaders() {
        final ArmeriaHttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder()
                                                                           .method(HttpMethod.GET)
                                                                           .path("/test"));

        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP);

        assertThat(in.buildRequestHeaders()).hasSize(2);
    }

    @Test
    void stripTEHeadersExcludingTrailers() {
        final ArmeriaHttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder()
                                                                           .method(HttpMethod.GET)
                                                                           .path("/test"));

        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP);
        in.add(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS);

        assertThat(in.buildRequestHeaders().get(HttpHeaderNames.TE))
                .isEqualTo(HttpHeaderValues.TRAILERS.toString());
    }

    @Test
    void stripTEHeadersCsvSeparatedExcludingTrailers() {
        final ArmeriaHttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder()
                                                                           .method(HttpMethod.GET)
                                                                           .path("/test"));

        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP + "," + HttpHeaderValues.TRAILERS);

        assertThat(in.buildRequestHeaders().get(HttpHeaderNames.TE))
                .isEqualTo(HttpHeaderValues.TRAILERS.toString());
    }

    @Test
    void stripTEHeadersCsvSeparatedAccountsForValueSimilarToTrailers() {
        final ArmeriaHttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder()
                                                                           .method(HttpMethod.GET)
                                                                           .path("/test"));

        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP + "," + HttpHeaderValues.TRAILERS + "foo");

        assertThat(in.buildRequestHeaders().contains(HttpHeaderNames.TE)).isFalse();
    }

    @Test
    void stripTEHeadersAccountsForValueSimilarToTrailers() {
        final ArmeriaHttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder()
                                                                           .method(HttpMethod.GET)
                                                                           .path("/test"));

        in.add(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS + "foo");

        assertThat(in.buildRequestHeaders().contains(HttpHeaderNames.TE)).isFalse();
    }

    @Test
    void stripTEHeadersAccountsForOWS() {
        // Disable headers validation to allow optional whitespace.
        final ArmeriaHttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder()
                                                                           .method(HttpMethod.GET)
                                                                           .path("/test"));

        in.add(HttpHeaderNames.TE, " " + HttpHeaderValues.TRAILERS + ' ');

        assertThat(in.buildRequestHeaders().get(HttpHeaderNames.TE))
                .isEqualTo(HttpHeaderValues.TRAILERS.toString());
    }

    @Test
    void stripConnectionHeadersAndNominees() {
        final ArmeriaHttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder()
                                                                           .method(HttpMethod.GET)
                                                                           .path("/test"));

        in.add(HttpHeaderNames.CONNECTION, "foo");
        in.add("foo", "bar");

        assertThat(in.buildRequestHeaders()).hasSize(2);
    }

    @Test
    void stripConnectionNomineesWithCsv() {
        final ArmeriaHttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder()
                                                                           .method(HttpMethod.GET)
                                                                           .path("/test"));

        in.add(HttpHeaderNames.CONNECTION, "foo,  bar");
        in.add("foo", "baz");
        in.add("bar", "qux");
        in.add("hello", "world");

        assertThat(in.buildRequestHeaders()).hasSize(3);
        assertThat(in.buildRequestHeaders().get(HttpHeaderNames.of("hello"))).isEqualTo("world");
    }
}
