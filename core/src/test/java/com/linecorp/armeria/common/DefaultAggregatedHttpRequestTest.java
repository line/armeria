/*
 * Copyright 2019 LINE Corporation
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

import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_LENGTH;
import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_MD5;
import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE;
import static com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class DefaultAggregatedHttpRequestTest {

    @Test
    void toHttpRequest() {
        final AggregatedHttpRequest aReq = AggregatedHttpRequest.of(
                HttpMethod.POST, "/foo", PLAIN_TEXT_UTF_8, "bar");
        final HttpRequest req = aReq.toHttpRequest();
        final List<HttpObject> drained = req.drainAll().join();

        assertThat(req.headers()).isEqualTo(RequestHeaders.of(HttpMethod.POST, "/foo",
                                                              CONTENT_TYPE, PLAIN_TEXT_UTF_8,
                                                              CONTENT_LENGTH, 3));
        assertThat(drained).containsExactly(HttpData.of(StandardCharsets.UTF_8, "bar"));
    }

    @Test
    void toHttpRequestWithoutContent() {
        final AggregatedHttpRequest aReq = AggregatedHttpRequest.of(HttpMethod.GET, "/bar");
        final HttpRequest req = aReq.toHttpRequest();
        final List<HttpObject> drained = req.drainAll().join();

        assertThat(req.headers()).isEqualTo(RequestHeaders.of(HttpMethod.GET, "/bar"));
        assertThat(drained).isEmpty();
    }

    @Test
    void toHttpRequestWithTrailers() {
        final AggregatedHttpRequest aReq = AggregatedHttpRequest.of(
                HttpMethod.PUT, "/baz", PLAIN_TEXT_UTF_8, HttpData.ofUtf8("bar"),
                HttpHeaders.of(CONTENT_MD5, "37b51d194a7513e45b56f6524f2d51f2"));
        final HttpRequest req = aReq.toHttpRequest();
        final List<HttpObject> drained = req.drainAll().join();

        assertThat(req.headers()).isEqualTo(RequestHeaders.of(HttpMethod.PUT, "/baz",
                                                              CONTENT_TYPE, PLAIN_TEXT_UTF_8,
                                                              CONTENT_LENGTH, 3));
        assertThat(drained).containsExactly(
                HttpData.of(StandardCharsets.UTF_8, "bar"),
                HttpHeaders.of(CONTENT_MD5, "37b51d194a7513e45b56f6524f2d51f2"));
    }
}
