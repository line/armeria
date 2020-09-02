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
package com.linecorp.armeria.spring.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;
import org.springframework.http.ResponseCookie;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;

import io.netty.util.concurrent.ImmediateEventExecutor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

public class ArmeriaClientHttpResponseTest {

    @Test
    public void readBodyStream() {
        final ResponseHeaders httpHeaders = ResponseHeaders.of(HttpStatus.OK);
        final HttpResponse httpResponse = HttpResponse.of(
                Flux.concat(Mono.just(httpHeaders),
                            Flux.just("a", "b", "c", "d", "e")
                                .map(HttpData::ofUtf8)));
        final ArmeriaClientHttpResponse response =
                response(new ArmeriaHttpResponseBodyStream(httpResponse, ImmediateEventExecutor.INSTANCE),
                         httpHeaders);

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.OK);

        assertThat(httpResponse.whenComplete().isDone()).isFalse();

        final Flux<String> body = response.getBody().map(TestUtil::bufferToString);
        StepVerifier.create(body, 1)
                    .expectNext("a").thenRequest(1)
                    .expectNext("b").thenRequest(1)
                    .expectNext("c").thenRequest(1)
                    .expectNext("d").thenRequest(1)
                    .expectNext("e").thenRequest(1)
                    .expectComplete()
                    .verify();

        await().until(() -> httpResponse.whenComplete().isDone());
    }

    @Test
    public void getCookies() {
        final HttpHeaders httpHeaders = ResponseHeaders.of(HttpStatus.OK,
                                                           HttpHeaderNames.of("blahblah"), "armeria",
                                                           HttpHeaderNames.SET_COOKIE, "a=1; b=2");
        final HttpResponse httpResponse = HttpResponse.of(httpHeaders);
        final ArmeriaClientHttpResponse response =
                response(new ArmeriaHttpResponseBodyStream(httpResponse, ImmediateEventExecutor.INSTANCE),
                         httpHeaders);

        // HttpResponse would be completed after ResponseHeader is completed, because there's no body.
        assertThat(httpResponse.whenComplete().isDone()).isTrue();

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("blahblah")).isEqualTo("armeria");

        final ResponseCookie cookie = response.getCookies().getFirst("a");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("1");
    }

    @Test
    public void cancel() {
        final AtomicBoolean completedWithError = new AtomicBoolean();
        final Flux<HttpData> bodyPub = Flux.just("a", "b", "c", "d", "e")
                                           .map(HttpData::ofUtf8)
                                           .doOnCancel(() -> completedWithError.set(true));

        final ResponseHeaders httpHeaders = ResponseHeaders.of(HttpStatus.OK);
        final HttpResponse httpResponse = HttpResponse.of(Flux.concat(Mono.just(httpHeaders), bodyPub));
        final ArmeriaClientHttpResponse response =
                response(new ArmeriaHttpResponseBodyStream(httpResponse, ImmediateEventExecutor.INSTANCE),
                         httpHeaders);

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.OK);

        assertThat(httpResponse.whenComplete().isDone()).isFalse();

        final Flux<String> body = response.getBody().map(TestUtil::bufferToString);
        StepVerifier.create(body, 1)
                    .expectNext("a").thenRequest(1)
                    .expectNext("b")
                    .thenCancel()
                    .verify();

        final CompletableFuture<Void> f = httpResponse.whenComplete();
        await().until(f::isDone);
        assertThat(f.isCompletedExceptionally()).isTrue();
        assertThatThrownBy(f::get).isInstanceOf(ExecutionException.class)
                                  .hasCauseInstanceOf(CancelledSubscriptionException.class);

        // Check whether the cancellation has been propagated to the original publisher.
        await().untilTrue(completedWithError);
    }

    private static ArmeriaClientHttpResponse response(ArmeriaHttpResponseBodyStream bodyStream,
                                                      HttpHeaders expectedHttpHeaders) {
        final ResponseHeaders h = bodyStream.headers().join();
        assertThat(h).isEqualTo(expectedHttpHeaders);

        return new ArmeriaClientHttpResponse(h, bodyStream, DataBufferFactoryWrapper.DEFAULT);
    }
}
