/*
 * Copyright 2025 LINE Corporation
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
import com.linecorp.armeria.internal.testing.AnticipatedException;

import reactor.test.StepVerifier;

class SplitHttpMessageTest {

    @Test
    void unsplit_request_trailers() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/foo",
                                                         HttpHeaderNames.CONTENT_LENGTH, 11);
        final StreamMessage<HttpData> body = StreamMessage.of(HttpData.ofUtf8("Hello "),
                                                              HttpData.ofUtf8("World"));
        final HttpHeaders trailers = HttpHeaders.of("foo", "bar");
        final HttpRequest request = HttpRequest.of(headers, body, trailers);
        final SplitHttpRequest split = request.split();
        assertThat(split.headers()).isEqualTo(headers);

        final HttpRequest unsplit = split.unsplit();
        assertThat(unsplit.headers()).isEqualTo(headers);
        StepVerifier.create(unsplit)
                    .expectNext(HttpData.ofUtf8("Hello "))
                    .expectNext(HttpData.ofUtf8("World"))
                    .expectNext(trailers)
                    .expectComplete()
                    .verify();
    }

    @Test
    void unsplit_failed_request() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/foo",
                                                         HttpHeaderNames.CONTENT_LENGTH, 11);
        final StreamMessage<HttpData> body = StreamMessage.aborted(new AnticipatedException("Failed!"));
        final HttpRequest request = HttpRequest.of(headers, body);
        final SplitHttpRequest split = request.split();

        final HttpRequest unsplit = split.unsplit();
        assertThat(unsplit.headers()).isEqualTo(headers);
        StepVerifier.create(unsplit)
                    .expectErrorSatisfies(t -> {
                        assertThat(t)
                                .hasMessageContaining("Failed!")
                                .isInstanceOf(AnticipatedException.class);
                    })
                    .verify();
    }

    @Test
    void unsplit_request_emptyTrailers() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/foo",
                                                         HttpHeaderNames.CONTENT_LENGTH, 11);
        final StreamMessage<HttpData> body = StreamMessage.of(HttpData.ofUtf8("Hello "),
                                                              HttpData.ofUtf8("World"));
        final HttpRequest request = HttpRequest.of(headers, body);
        final SplitHttpRequest split = request.split();
        assertThat(split.headers()).isEqualTo(headers);

        final HttpRequest unsplit = split.unsplit();
        assertThat(unsplit.headers()).isEqualTo(headers);
        StepVerifier.create(unsplit)
                    .expectNext(HttpData.ofUtf8("Hello "))
                    .expectNext(HttpData.ofUtf8("World"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void unsplit_request_subscribed() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/foo",
                                                         HttpHeaderNames.CONTENT_LENGTH, 11);
        final StreamMessage<HttpData> body = StreamMessage.of(HttpData.ofUtf8("Hello "),
                                                              HttpData.ofUtf8("World"));
        final HttpRequest request = HttpRequest.of(headers, body);
        final SplitHttpRequest split = request.split();
        split.body().collect();

        final HttpRequest unsplit = split.unsplit();
        assertThat(unsplit.headers()).isEqualTo(headers);
        StepVerifier.create(unsplit)
                    .expectErrorSatisfies(t -> {
                        assertThat(t)
                                .hasMessageContaining("Only single subscriber is allowed")
                                .isInstanceOf(IllegalStateException.class);
                    })
                    .verify();
    }

    @Test
    void unsplit_response_trailers() {
        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK);
        final StreamMessage<HttpData> body = StreamMessage.of(HttpData.ofUtf8("Hello "),
                                                              HttpData.ofUtf8("World"));
        final HttpHeaders trailers = HttpHeaders.of("foo", "bar");
        final HttpResponse response = HttpResponse.of(headers, body, trailers);
        final SplitHttpResponse split = response.split();
        assertThat(split.headers().join()).isEqualTo(headers);

        final HttpResponse unsplit = split.unsplit();
        StepVerifier.create(unsplit)
                    .expectNext(headers)
                    .expectNext(HttpData.ofUtf8("Hello "))
                    .expectNext(HttpData.ofUtf8("World"))
                    .expectNext(trailers)
                    .expectComplete()
                    .verify();
    }

    @Test
    void unsplit_response_failed() {
        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK);
        final StreamMessage<HttpData> body = StreamMessage.aborted(new AnticipatedException("Failed!"));
        final HttpHeaders trailers = HttpHeaders.of("foo", "bar");
        final HttpResponse response = HttpResponse.of(headers, body, trailers);
        final SplitHttpResponse split = response.split();
        assertThat(split.headers().join()).isEqualTo(headers);

        final HttpResponse unsplit = split.unsplit();
        StepVerifier.create(unsplit)
                    .expectNext(headers)
                    .expectErrorSatisfies(t -> {
                        assertThat(t)
                                .hasMessageContaining("Failed!")
                                .isInstanceOf(AnticipatedException.class);
                    })
                    .verify();
    }

    @Test
    void unsplit_response_emptyTrailers() {
        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK);
        final StreamMessage<HttpData> body = StreamMessage.of(HttpData.ofUtf8("Hello "),
                                                              HttpData.ofUtf8("World"));
        final HttpResponse response = HttpResponse.of(headers, body);
        final SplitHttpResponse split = response.split();
        assertThat(split.headers().join()).isEqualTo(headers);

        final HttpResponse unsplit = split.unsplit();
        StepVerifier.create(unsplit)
                    .expectNext(headers)
                    .expectNext(HttpData.ofUtf8("Hello "))
                    .expectNext(HttpData.ofUtf8("World"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void unsplit_response_subscribed() {
        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK);
        final StreamMessage<HttpData> body = StreamMessage.of(HttpData.ofUtf8("Hello "),
                                                              HttpData.ofUtf8("World"));
        final HttpResponse response = HttpResponse.of(headers, body);
        final SplitHttpResponse split = response.split();
        assertThat(split.headers().join()).isEqualTo(headers);
        split.body().collect();

        final HttpResponse unsplit = split.unsplit();
        StepVerifier.create(unsplit)
                    .expectNext(headers)
                    .expectErrorSatisfies(t -> {
                        assertThat(t)
                                .hasMessageContaining("Only single subscriber is allowed")
                                .isInstanceOf(IllegalStateException.class);
                    })
                    .verify();
    }
}
