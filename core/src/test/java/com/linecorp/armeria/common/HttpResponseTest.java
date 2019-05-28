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
}
