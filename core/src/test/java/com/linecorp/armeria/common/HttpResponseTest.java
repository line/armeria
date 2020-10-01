/*
 * Copyright 2018 LINE Corporation
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

import org.junit.jupiter.api.Test;

class HttpResponseTest {

    @Test
    void ofWithPlainContent() {
        // Using non-ascii to test UTF-8 conversion
        final HttpResponse res = HttpResponse.of("Armeriaはいろんな使い方がアルメリア");
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.contentUtf8())
                .isEqualTo("Armeriaはいろんな使い方がアルメリア");
    }

    @Test
    void ofWithPlainFormat() {
        // Using non-ascii to test UTF-8 conversion
        final HttpResponse res = HttpResponse.of(
                "%sはいろんな使い方が%s", "Armeria", "アルメリア");
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.contentUtf8())
                .isEqualTo("Armeriaはいろんな使い方がアルメリア");
    }

    @Test
    void ofWithContent() {
        // Using non-ascii to test UTF-8 conversion
        final HttpResponse res = HttpResponse.of(
                MediaType.PLAIN_TEXT_UTF_8, "Armeriaはいろんな使い方がアルメリア");
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.contentUtf8())
                .isEqualTo("Armeriaはいろんな使い方がアルメリア");
    }

    @Test
    void ofWithFormat() {
        // Using non-ascii to test UTF-8 conversion
        final HttpResponse res = HttpResponse.of(
                MediaType.PLAIN_TEXT_UTF_8,
                "%sはいろんな使い方が%s", "Armeria", "アルメリア");
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedRes.contentUtf8())
                .isEqualTo("Armeriaはいろんな使い方がアルメリア");
    }

    @Test
    void shouldReleaseEmptyContent() {
        EmptyPooledHttpData data = new EmptyPooledHttpData();
        HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, data);
        assertThat(data.refCnt()).isZero();

        data = new EmptyPooledHttpData();
        HttpResponse.of(ResponseHeaders.of(200), data);
        assertThat(data.refCnt()).isZero();

        data = new EmptyPooledHttpData();
        HttpResponse.of(ResponseHeaders.of(200),
                        data,
                        HttpHeaders.of("some-trailer", "value"));
        assertThat(data.refCnt()).isZero();
    }

    @Test
    void statusOfResponseHeadersShouldNotBeInformational() {
        assertThatThrownBy(() -> HttpResponse.of(HttpStatus.CONTINUE, MediaType.PLAIN_TEXT_UTF_8,
                                                 HttpData.ofUtf8("bob")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-1xx");
    }

    @Test
    void ofRedirectTemporary() {
        final HttpResponse res = HttpResponse.ofRedirect("locationFor");
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
        assertThat(aggregatedRes.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("locationFor");
    }

    @Test
    void ofRedirectTemporaryUsingFormat() {
        final HttpResponse res = HttpResponse.ofRedirect("location%s", "For");
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
        assertThat(aggregatedRes.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("locationFor");
    }

    @Test
    void ofRedirectPermanently() {
        final HttpResponse res = HttpResponse.ofRedirect(HttpStatus.MOVED_PERMANENTLY, "locationFor");
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.MOVED_PERMANENTLY);
        assertThat(aggregatedRes.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("locationFor");
    }

    @Test
    void ofRedirectPermanentlyUsingStringFormat() {
        final HttpResponse res = HttpResponse.ofRedirect(HttpStatus.MOVED_PERMANENTLY, "location%s", "For");
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.MOVED_PERMANENTLY);
        assertThat(aggregatedRes.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("locationFor");
    }

    @Test
    void ofRedirectResponseCodeShouldBe300to307() {
        assertThatThrownBy(() -> HttpResponse.ofRedirect(HttpStatus.OK, "locationFor"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expected: 300 .. 307");
    }

    @Test
    void ofRedirectParamsShouldNotBeNull() {
        //check redirect status
        assertThatThrownBy(() -> HttpResponse.ofRedirect(null, "locationFor"))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("redirectStatus");

        //check location
        assertThatThrownBy(() -> HttpResponse.ofRedirect(null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("location");

        assertThatThrownBy(() -> HttpResponse.ofRedirect(HttpStatus.MOVED_PERMANENTLY, null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("location");

        //check args
        assertThatThrownBy(() -> HttpResponse.ofRedirect("locationFor", null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("args");

        assertThatThrownBy(() -> HttpResponse.ofRedirect(HttpStatus.MOVED_PERMANENTLY, "locationFor : %s",
            null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("args");
    }
}
