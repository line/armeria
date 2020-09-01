/*
 * Copyright 2020 LINE Corporation
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

import com.linecorp.armeria.client.ResponseTimeoutException;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class DefaultHttpResponseBodyStreamTest {

    @Test
    void emptyBody() {
        final HttpResponse response = HttpResponse.of(HttpStatus.NO_CONTENT);
        final HttpResponseBodyStream bodyStream = response.toBodyStream();
        StepVerifier.create(bodyStream)
                    .thenRequest(1)
                    .expectNextCount(0)
                    .verifyComplete();
        assertThat(bodyStream.headers().join().status()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void completeHeadersBeforeConsumeBody() {
        final HttpResponse response = HttpResponse.of(HttpStatus.OK);
        final HttpResponseBodyStream bodyStream = response.toBodyStream();
        assertThat(bodyStream.headers().join().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void informationalHeaders() {
        final HttpResponse response = HttpResponse.of(ResponseHeaders.of(HttpStatus.CONTINUE),
                                                      ResponseHeaders.of(HttpStatus.PROCESSING),
                                                      ResponseHeaders.of(HttpStatus.OK),
                                                      HttpData.ofUtf8("Hello"));
        final HttpResponseBodyStream bodyStream = response.toBodyStream();

        StepVerifier.create(bodyStream)
                    .thenRequest(1)
                    .expectNext(HttpData.ofUtf8("Hello"))
                    .verifyComplete();
        assertThat(bodyStream.informationalHeaders().join()).containsExactly(
                ResponseHeaders.of(HttpStatus.CONTINUE), ResponseHeaders.of(HttpStatus.PROCESSING));
        assertThat(bodyStream.headers().join().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void trailers() {
        final HttpResponse response = HttpResponse.of(Flux.just(ResponseHeaders.of(HttpStatus.OK),
                                                                HttpData.ofUtf8("Hello1"),
                                                                HttpData.ofUtf8("Hello2"),
                                                                HttpHeaders.of("grpc-status", "0")));
        final HttpResponseBodyStream bodyStream = response.toBodyStream();

        assertThat(bodyStream.trailers()).isNotDone();
        StepVerifier.create(bodyStream)
                    .thenRequest(2)
                    .expectNextCount(2)
                    .verifyComplete();
        assertThat(bodyStream.trailers().join().get("grpc-status")).isEqualTo("0");
    }

    @Test
    void publisherBasedResponse() {
        final HttpResponse response = HttpResponse.of(Flux.just(ResponseHeaders.of(HttpStatus.OK),
                                                                HttpData.ofUtf8("Hello1"),
                                                                HttpData.ofUtf8("Hello2")));

        final HttpResponseBodyStream bodyStream = response.toBodyStream();
        StepVerifier.create(bodyStream)
                    .thenRequest(1)
                    .expectNext(HttpData.ofUtf8("Hello1"))
                    .thenRequest(1)
                    .expectNext(HttpData.ofUtf8("Hello2"))
                    .verifyComplete();
    }

    @Test
    void failedResponse() {
        final HttpResponse response = HttpResponse.ofFailure(ResponseTimeoutException.get());
        final HttpResponseBodyStream bodyStream = response.toBodyStream();
        StepVerifier.create(bodyStream)
                    .thenRequest(1)
                    .expectError(ResponseTimeoutException.class)
                    .verify();
        assertThat(bodyStream.informationalHeaders().join()).isEmpty();
        assertThat(bodyStream.headers().join()).isEqualTo(ResponseHeaders.of(HttpStatus.UNKNOWN));
        assertThat(bodyStream.trailers().join().isEmpty()).isTrue();
    }

    @Test
    void cancelResponse() {
        final HttpResponse response = HttpResponse.of(Flux.just(ResponseHeaders.of(HttpStatus.OK),
                                                                HttpData.ofUtf8("Hello1"),
                                                                HttpData.ofUtf8("Hello2"),
                                                                HttpHeaders.of("grpc-status", 0)));
        final HttpResponseBodyStream bodyStream = response.toBodyStream();
        StepVerifier.create(bodyStream)
                    .thenCancel()
                    .verify();

        assertThat(bodyStream.informationalHeaders().join()).isEmpty();
        assertThat(bodyStream.headers().join()).isEqualTo(ResponseHeaders.of(HttpStatus.OK));
        assertThat(bodyStream.trailers().join().isEmpty()).isTrue();
    }
}
