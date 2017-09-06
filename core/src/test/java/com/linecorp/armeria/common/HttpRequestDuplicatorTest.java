/*
 * Copyright 2017 LINE Corporation
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

import org.junit.Test;

public class HttpRequestDuplicatorTest {

    @Test
    public void aggregateTwice() {
        final AggregatedHttpMessage aReq = AggregatedHttpMessage.of(
                HttpMethod.PUT, "/foo", PLAIN_TEXT_UTF_8, HttpData.ofUtf8("bar"),
                HttpHeaders.of(CONTENT_MD5, "37b51d194a7513e45b56f6524f2d51f2"));

        final HttpRequest publisher = aReq.toHttpRequest();
        final HttpRequestDuplicator reqDuplicator = new HttpRequestDuplicator(publisher);

        final AggregatedHttpMessage req1 = reqDuplicator.duplicateStream().aggregate().join();
        final AggregatedHttpMessage req2 = reqDuplicator.duplicateStream().aggregate().join();

        assertThat(req1.headers()).isEqualTo(
                HttpHeaders.of(HttpMethod.PUT, "/foo")
                           .setObject(CONTENT_TYPE, PLAIN_TEXT_UTF_8)
                           .setInt(CONTENT_LENGTH, 3));
        assertThat(req1.content()).isEqualTo(HttpData.of(StandardCharsets.UTF_8, "bar"));
        assertThat(req1.trailingHeaders()).isEqualTo(
                HttpHeaders.of(CONTENT_MD5, "37b51d194a7513e45b56f6524f2d51f2"));

        assertThat(req2.headers()).isEqualTo(
                HttpHeaders.of(HttpMethod.PUT, "/foo")
                           .setObject(CONTENT_TYPE, PLAIN_TEXT_UTF_8)
                           .setInt(CONTENT_LENGTH, 3));
        assertThat(req2.content()).isEqualTo(HttpData.of(StandardCharsets.UTF_8, "bar"));
        assertThat(req2.trailingHeaders()).isEqualTo(
                HttpHeaders.of(CONTENT_MD5, "37b51d194a7513e45b56f6524f2d51f2"));
        reqDuplicator.close();
    }
}
