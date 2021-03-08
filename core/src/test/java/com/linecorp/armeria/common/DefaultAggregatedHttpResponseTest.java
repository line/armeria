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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import reactor.test.StepVerifier;

class DefaultAggregatedHttpResponseTest {

    @Test
    void toHttpResponse() {
        final AggregatedHttpResponse aRes = AggregatedHttpResponse.of(
                HttpStatus.OK, PLAIN_TEXT_UTF_8, "alice");
        final HttpResponse res = aRes.toHttpResponse();
        StepVerifier.create(res)
                    .expectNext(ResponseHeaders.of(HttpStatus.OK,
                                                   CONTENT_TYPE, PLAIN_TEXT_UTF_8,
                                                   CONTENT_LENGTH, 5))
                    .expectNext(HttpData.of(StandardCharsets.UTF_8, "alice"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void toHttpResponseWithoutContent() {
        final AggregatedHttpResponse aRes = AggregatedHttpResponse.of(HttpStatus.OK, PLAIN_TEXT_UTF_8,
                                                                      HttpData.empty());
        final HttpResponse res = aRes.toHttpResponse();
        StepVerifier.create(res)
                    .expectNext(ResponseHeaders.of(HttpStatus.OK,
                                                   CONTENT_TYPE, PLAIN_TEXT_UTF_8,
                                                   CONTENT_LENGTH, 0))
                    .expectComplete()
                    .verify();
    }

    @Test
    void toHttpResponseWithTrailers() {
        final AggregatedHttpResponse aRes = AggregatedHttpResponse.of(
                HttpStatus.OK, PLAIN_TEXT_UTF_8, HttpData.ofUtf8("bob"),
                HttpHeaders.of(CONTENT_MD5, "9f9d51bc70ef21ca5c14f307980a29d8"));
        final HttpResponse res = aRes.toHttpResponse();
        StepVerifier.create(res)
                    .expectNext(ResponseHeaders.of(HttpStatus.OK, CONTENT_TYPE, PLAIN_TEXT_UTF_8))
                    .expectNext(HttpData.of(StandardCharsets.UTF_8, "bob"))
                    .expectNext(HttpHeaders.of(CONTENT_MD5, "9f9d51bc70ef21ca5c14f307980a29d8"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void toHttpResponseWithInformationals() {
        final AggregatedHttpResponse aRes = AggregatedHttpResponse.of(
                ImmutableList.of(ResponseHeaders.of(HttpStatus.CONTINUE)),
                ResponseHeaders.of(HttpStatus.OK), HttpData.empty(), HttpHeaders.of());

        final HttpResponse res = aRes.toHttpResponse();
        StepVerifier.create(res)
                    .expectNext(ResponseHeaders.of(HttpStatus.CONTINUE))
                    .expectNext(ResponseHeaders.of(HttpStatus.OK, CONTENT_LENGTH, 0))
                    .expectComplete()
                    .verify();
    }

    @Test
    void errorWhenContentShouldBeEmpty() {
        contentShouldBeEmpty(HttpStatus.NO_CONTENT, HttpData.ofUtf8("bob"));
        contentShouldBeEmpty(HttpStatus.RESET_CONTENT, HttpData.ofUtf8("bob"));
        contentShouldBeEmpty(HttpStatus.NOT_MODIFIED, HttpData.ofUtf8("bob"));
    }

    private static void contentShouldBeEmpty(HttpStatus status, HttpData content) {
        assertThatThrownBy(() -> AggregatedHttpResponse.of(status, PLAIN_TEXT_UTF_8, content))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusOfResponseHeadersShouldNotBeInformational() {
        assertThatThrownBy(() -> AggregatedHttpResponse.of(HttpStatus.CONTINUE, PLAIN_TEXT_UTF_8,
                                                           HttpData.ofUtf8("bob")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-1xx");
    }

    @Test
    void contentLengthIsNotSetWhen204Or205() {
        ResponseHeaders headers = ResponseHeaders.of(HttpStatus.NO_CONTENT, CONTENT_LENGTH, 100);
        assertThat(AggregatedHttpResponse.of(headers).headers().get(CONTENT_LENGTH)).isNull();

        headers = ResponseHeaders.of(HttpStatus.RESET_CONTENT, CONTENT_LENGTH, 100);
        assertThat(AggregatedHttpResponse.of(headers).headers().get(CONTENT_LENGTH)).isNull();

        // 304 response can have the 'Content-length' header when it is a response to a conditional
        // GET request. See https://datatracker.ietf.org/doc/html/rfc7230#section-3.3.2
        headers = ResponseHeaders.of(HttpStatus.NOT_MODIFIED, CONTENT_LENGTH, 100);
        assertThat(AggregatedHttpResponse.of(headers).headers().getInt(CONTENT_LENGTH)).isEqualTo(100);
    }

    @Test
    void contentLengthIsSet() {
        AggregatedHttpResponse res = AggregatedHttpResponse.of(HttpStatus.OK);
        assertThat(res.headers().getInt(CONTENT_LENGTH)).isEqualTo(6); // the length of status.toHttpData()

        res = AggregatedHttpResponse.of(HttpStatus.OK, PLAIN_TEXT_UTF_8, HttpData.ofUtf8("foo"));
        assertThat(res.headers().getInt(CONTENT_LENGTH)).isEqualTo(3);

        res = AggregatedHttpResponse.of(HttpStatus.OK, PLAIN_TEXT_UTF_8, HttpData.ofUtf8(""));
        assertThat(res.headers().getInt(CONTENT_LENGTH)).isEqualTo(0);

        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK, CONTENT_LENGTH, 1000000);
        // It can have 'Content-length' even though it does not have content, because it can be a response
        // to a HEAD request.
        assertThat(AggregatedHttpResponse.of(headers).headers().getInt(CONTENT_LENGTH)).isEqualTo(1000000);

        res = AggregatedHttpResponse.of(headers, HttpData.ofUtf8("foo"));
        assertThat(res.headers().getInt(CONTENT_LENGTH)).isEqualTo(3); // The length is reset to 3 from 1000000.
    }
}
