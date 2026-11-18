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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.internal.common.JacksonUtil;

import reactor.test.StepVerifier;

class HttpRequestTest {

    @Test
    void createWithVarArgs() {
        final RequestHeaders requestHeaders = RequestHeaders.of(HttpMethod.GET, "/foo");
        final HttpRequest request = HttpRequest.of(requestHeaders, HttpData.ofUtf8("a"), HttpData.ofUtf8("b"),
                                                   HttpData.ofUtf8("c"));
        StepVerifier.create(request)
                    .expectNext(HttpData.ofUtf8("a"))
                    .expectNext(HttpData.ofUtf8("b"))
                    .expectNext(HttpData.ofUtf8("c"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void abortWithCause() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/foo", MediaType.PLAIN_TEXT, "foo");
        final AtomicReference<Throwable> abortCauseHolder = new AtomicReference<>();
        request.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription subscription) {}

            @Override
            public void onNext(HttpObject httpObject) {}

            @Override
            public void onError(Throwable throwable) {
                abortCauseHolder.set(throwable);
            }

            @Override
            public void onComplete() {}
        });
        final IllegalStateException abortCause = new IllegalStateException("abort stream");
        request.abort(abortCause);
        await().untilAsserted(() -> {
            assertThat(abortCauseHolder).hasValue(abortCause);
        });
    }

    @Test
    void completeOnSubscribing() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/foo");
        final AtomicBoolean completed = new AtomicBoolean();
        request.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription subscription) {}

            @Override
            public void onNext(HttpObject httpObject) {}

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });
        await().untilTrue(completed);
    }

    @Test
    void shouldReleaseEmptyContent() {
        EmptyPooledHttpData data = new EmptyPooledHttpData();
        HttpRequest.of(HttpMethod.GET, "/", MediaType.PLAIN_TEXT_UTF_8, data);
        assertThat(data.refCnt()).isZero();

        data = new EmptyPooledHttpData();
        HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/"), data);
        assertThat(data.refCnt()).isZero();

        data = new EmptyPooledHttpData();
        HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/"),
                       data,
                       HttpHeaders.of("some-trailer", "value"));
        assertThat(data.refCnt()).isZero();
    }

    @Test
    void ofJson() {
        final Map<String, String> content = Collections.singletonMap("key", "value");
        final HttpRequest request = HttpRequest.ofJson(HttpMethod.POST, "/json", content);
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.path()).isEqualTo("/json");
        assertThat(request.headers().contentType()).isEqualTo(MediaType.JSON);

        final AggregatedHttpRequest aggregated = request.aggregate().join();
        assertThat(aggregated.contentUtf8()).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    void ofJsonWithCustomMapper() {
        final Map<String, String> content = Collections.singletonMap("key", "value");
        final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();
        final HttpRequest request = HttpRequest.ofJson(HttpMethod.PUT, "/json_custom", content, mapper);
        assertThat(request.method()).isEqualTo(HttpMethod.PUT);
        assertThat(request.path()).isEqualTo("/json_custom");
        assertThat(request.headers().contentType()).isEqualTo(MediaType.JSON);

        final AggregatedHttpRequest aggregated = request.aggregate().join();
        assertThat(aggregated.contentUtf8()).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    void ofJsonNullValidation() {
        final Map<String, String> content = Collections.singletonMap("key", "value");
        final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

        assertThatThrownBy(() -> HttpRequest.ofJson(null, "/path", content))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> HttpRequest.ofJson(HttpMethod.POST, null, content))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> HttpRequest.ofJson(HttpMethod.POST, "/path", null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> HttpRequest.ofJson(null, "/path", content, mapper))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> HttpRequest.ofJson(HttpMethod.POST, null, content, mapper))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> HttpRequest.ofJson(HttpMethod.POST, "/path", null, mapper))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> HttpRequest.ofJson(HttpMethod.POST, "/path", content, null))
                .isInstanceOf(NullPointerException.class);
    }
}
