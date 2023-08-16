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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AggregatedHttpObjectTest {

    @Test
    void testDecodingContentCorrectly() {
        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.GET, "/",
                HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        final HttpData httpData = HttpData.of(StandardCharsets.UTF_8, "foo");

        final AggregatedHttpRequest request = AggregatedHttpRequest.of(headers, httpData);

        final Charset contentTypeCharset  = request.headers().contentType().charset();

        assertThat(contentTypeCharset).isEqualTo(StandardCharsets.UTF_8);
        assertThat(request.content(StandardCharsets.UTF_16BE)).isEqualTo("foo");
        assertThat(request.content(StandardCharsets.UTF_8)).isEqualTo("foo");
    }

    @Test
    void testDecodingContentCorrectlyWhenContentTypeIsNull() {
        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.GET, "/");
        final HttpData httpData = HttpData.of(StandardCharsets.UTF_8, "foo");

        final AggregatedHttpRequest request = AggregatedHttpRequest.of(headers, httpData);

        assertThat(request.content(StandardCharsets.UTF_16BE)).isNotEqualTo("foo");
        assertThat(request.content(StandardCharsets.UTF_8)).isEqualTo("foo");
    }
}
