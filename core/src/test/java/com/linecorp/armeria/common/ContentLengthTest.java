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

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.stream.StreamMessage;

class ContentLengthTest {
    @Test
    void shouldAllowMinusOne() {
        final HttpHeaders headers = HttpHeaders.builder()
                                               .contentLengthUnknown()
                                               .build();
        assertThat(headers.isContentLengthUnknown()).isTrue();
        assertThat(headers.contentLength()).isEqualTo(-1);
        assertThat(headers.get(HttpHeaderNames.CONTENT_LENGTH)).isNull();
    }

    @Test
    void shouldClearIsContentLengthUnknownWhenRemoved() {
        final HttpHeaders headers = HttpHeaders.builder()
                                               .contentLength(10)
                                               .build();
        assertThat(headers.isContentLengthUnknown()).isFalse();
        assertThat(headers.contentLength()).isEqualTo(10);
        final HttpHeaders withoutLength = headers.toBuilder()
                                                 .removeAndThen(HttpHeaderNames.CONTENT_LENGTH)
                                                 .build();
        assertThat(withoutLength.isContentLengthUnknown()).isFalse();
        assertThat(withoutLength.contentLength()).isEqualTo(-1);
    }

    @Test
    void shouldPreserveExplicitNullContentLengthWhenAggregated_request() {
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .method(HttpMethod.POST)
                                                     .path("/")
                                                     .contentLengthUnknown()
                                                     .contentType(MediaType.PLAIN_TEXT)
                                                     .build();
        final HttpRequest request = HttpRequest.of(headers, StreamMessage.of(HttpData.ofUtf8("foo")));
        assertThat(request.headers().contentLength()).isEqualTo(-1);
        assertThat(request.headers().isContentLengthUnknown()).isTrue();
        final AggregatedHttpRequest aggregatedRequest = request.aggregate().join();
        assertThat(aggregatedRequest.headers().contentLength()).isEqualTo(-1);
        assertThat(aggregatedRequest.headers().isContentLengthUnknown()).isTrue();
    }

    @Test
    void shouldPreserveExplicitNullContentLengthWhenAggregated_response() {
        final ResponseHeaders headers = ResponseHeaders.builder()
                                                       .status(HttpStatus.OK)
                                                       .contentLengthUnknown()
                                                       .contentType(MediaType.PLAIN_TEXT)
                                                       .build();
        final HttpResponse response = HttpResponse.of(headers, StreamMessage.of(HttpData.ofUtf8("foo")));
        final AggregatedHttpResponse aggregatedResponse = response.aggregate().join();
        assertThat(aggregatedResponse.headers().contentLength()).isEqualTo(-1);
        assertThat(aggregatedResponse.headers().isContentLengthUnknown()).isTrue();
    }

    @Test
    void shouldPreserveContentLengthWithEmptyContent_response() {
        final ResponseHeaders headers =
                ResponseHeaders.builder()
                               .status(HttpStatus.OK)
                               // A response to a HEAD request may have a content-length with an empty body.
                               .contentLength(10)
                               .contentType(MediaType.PLAIN_TEXT)
                               .build();
        final HttpResponse response = HttpResponse.of(headers);
        final AggregatedHttpResponse aggregatedResponse = response.aggregate().join();
        assertThat(aggregatedResponse.headers().contentLength()).isEqualTo(10);
    }

    @Test
    void shouldFixIncorrectContentLength_request() {
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .method(HttpMethod.POST)
                                                     .path("/")
                                                     .contentLength(2)
                                                     .contentType(MediaType.PLAIN_TEXT)
                                                     .build();
        HttpRequest request = HttpRequest.of(headers, HttpData.ofUtf8("foo"));
        assertThat(request.headers().contentLength()).isEqualTo(3);
        assertThat(request.headers().isContentLengthUnknown()).isFalse();
        AggregatedHttpRequest aggregatedRequest = request.aggregate().join();
        assertThat(aggregatedRequest.headers().contentLength()).isEqualTo(3);
        assertThat(aggregatedRequest.headers().isContentLengthUnknown()).isFalse();

        request = HttpRequest.of(headers, StreamMessage.of(HttpData.ofUtf8("foo")));
        // The content-length of a StreamMessage is unknown until it is aggregated.
        assertThat(request.headers().contentLength()).isEqualTo(2);
        assertThat(request.headers().isContentLengthUnknown()).isFalse();
        aggregatedRequest = request.aggregate().join();
        assertThat(aggregatedRequest.headers().contentLength()).isEqualTo(3);
        assertThat(aggregatedRequest.headers().isContentLengthUnknown()).isFalse();
    }

    @Test
    void shouldFixIncorrectContentLength_response() {
        final ResponseHeaders headers = ResponseHeaders.builder()
                                                       .status(HttpStatus.OK)
                                                       .contentLength(2)
                                                       .contentType(MediaType.PLAIN_TEXT)
                                                       .build();
        HttpResponse fixedResponse = HttpResponse.of(headers, HttpData.ofUtf8("foo"));
        ResponseHeaders splitHeaders = fixedResponse.split().headers().join();
        assertThat(splitHeaders.contentLength()).isEqualTo(3);
        assertThat(splitHeaders.isContentLengthUnknown()).isFalse();

        fixedResponse = HttpResponse.of(headers, HttpData.ofUtf8("foo"));
        AggregatedHttpResponse aggregatedResponse = fixedResponse.aggregate().join();
        assertThat(aggregatedResponse.headers().contentLength()).isEqualTo(3);
        assertThat(aggregatedResponse.headers().isContentLengthUnknown()).isFalse();

        HttpResponse streamResponse = HttpResponse.of(headers, StreamMessage.of(HttpData.ofUtf8("foo")));
        splitHeaders = streamResponse.split().headers().join();
        // The content-length of a StreamMessage is unknown until it is aggregated.
        assertThat(splitHeaders.contentLength()).isEqualTo(2);
        assertThat(splitHeaders.isContentLengthUnknown()).isFalse();

        streamResponse = HttpResponse.of(headers, StreamMessage.of(HttpData.ofUtf8("foo")));
        aggregatedResponse = streamResponse.aggregate().join();
        assertThat(aggregatedResponse.headers().contentLength()).isEqualTo(3);
        assertThat(aggregatedResponse.headers().isContentLengthUnknown()).isFalse();
    }
}
