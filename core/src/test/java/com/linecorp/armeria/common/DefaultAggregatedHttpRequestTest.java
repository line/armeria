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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_LENGTH;
import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_MD5;
import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE;
import static com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import reactor.test.StepVerifier;

class DefaultAggregatedHttpRequestTest {

    @Test
    void toHttpRequest() {
        final AggregatedHttpRequest aReq = AggregatedHttpRequest.of(
                HttpMethod.POST, "/foo", PLAIN_TEXT_UTF_8, "bar");
        final HttpRequest req = aReq.toHttpRequest();
        assertThat(req.headers()).isEqualTo(RequestHeaders.of(HttpMethod.POST, "/foo",
                                                              CONTENT_TYPE, PLAIN_TEXT_UTF_8,
                                                              CONTENT_LENGTH, 3));
        StepVerifier.create(req)
                    .expectNext(HttpData.of(StandardCharsets.UTF_8, "bar"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void toHttpRequestWithoutContent() {
        final AggregatedHttpRequest aReq = AggregatedHttpRequest.of(HttpMethod.GET, "/bar");
        final HttpRequest req = aReq.toHttpRequest();
        assertThat(req.headers()).isEqualTo(RequestHeaders.of(HttpMethod.GET, "/bar"));
        StepVerifier.create(req)
                    .expectComplete()
                    .verify();
    }

    @Test
    void toHttpRequestWithTrailers() {
        final AggregatedHttpRequest aReq = AggregatedHttpRequest.of(
                HttpMethod.PUT, "/baz", PLAIN_TEXT_UTF_8, HttpData.ofUtf8("bar"),
                HttpHeaders.of(CONTENT_MD5, "37b51d194a7513e45b56f6524f2d51f2"));
        final HttpRequest req = aReq.toHttpRequest();
        assertThat(req.headers()).isEqualTo(RequestHeaders.of(HttpMethod.PUT, "/baz",
                                                              CONTENT_TYPE, PLAIN_TEXT_UTF_8,
                                                              CONTENT_LENGTH, 3));
        StepVerifier.create(req)
                    .expectNext(HttpData.of(StandardCharsets.UTF_8, "bar"))
                    .expectNext(HttpHeaders.of(CONTENT_MD5, "37b51d194a7513e45b56f6524f2d51f2"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void requestAbortPropagatesException() {
        final HttpRequestWriter req = HttpRequest.streaming(HttpMethod.GET, "/");
        req.abort(new IllegalStateException("closed"));
        assertThatThrownBy(() -> req.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldHaveAllGettersInHttpRequest() throws Exception {
        final List<String> httpRequestMethods = noParameterMethods(HttpRequest.class);
        final List<String> aggregateHttpRequestMethods = noParameterMethods(AggregatedHttpRequest.class,
                                                                            AggregatedHttpMessage.class,
                                                                            AggregatedHttpObject.class);
        for (String httpRequestMethod : httpRequestMethods) {
            if (httpRequestMethod.startsWith("builder") || httpRequestMethod.startsWith("aggregate") ||
                httpRequestMethod.startsWith("toDuplicator")) {
                // Not a getter.
                continue;
            }
            assertThat(aggregateHttpRequestMethods).contains(httpRequestMethod);
        }
    }

    static List<String> noParameterMethods(Class<?>... classes) {
        return Arrays.stream(classes).flatMap(aClass -> Stream.of(aClass.getDeclaredMethods()))
                     .filter(method -> method.getParameterCount() == 0)
                     .map(Method::getName)
                     .collect(toImmutableList());
    }
}
