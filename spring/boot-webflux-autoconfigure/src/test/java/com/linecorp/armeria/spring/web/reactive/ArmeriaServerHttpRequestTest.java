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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.http.HttpCookie;
import org.springframework.util.MultiValueMap;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.server.ServiceRequestContext;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

public class ArmeriaServerHttpRequestTest {

    static final ServiceRequestContext ctx = mock(ServiceRequestContext.class);

    @BeforeClass
    public static void setup() {
        when(ctx.sslSession()).thenReturn(null);
        when(ctx.contextAwareExecutor()).thenReturn(MoreExecutors.directExecutor());
    }

    private static ArmeriaServerHttpRequest request(HttpRequest httpRequest) {
        return new ArmeriaServerHttpRequest(ctx, httpRequest, DataBufferFactoryWrapper.DEFAULT);
    }

    @Test
    public void readBodyStream() throws Exception {
        final HttpRequest httpRequest =
                HttpRequest.of(HttpHeaders.of(HttpMethod.POST, "/"),
                               Flux.just("a", "b", "c", "d", "e")
                                   .map(HttpData::ofUtf8));

        final ArmeriaServerHttpRequest req = request(httpRequest);
        assertThat(req.getMethodValue()).isEqualTo(HttpMethod.POST.name());
        assertThat(req.<Object>getNativeRequest()).isInstanceOf(HttpRequest.class).isEqualTo(httpRequest);

        assertThat(httpRequest.completionFuture().isDone()).isFalse();

        final Flux<String> body = req.getBody().map(TestUtil::bufferToString);
        StepVerifier.create(body, 1)
                    .expectNext("a").thenRequest(1)
                    .expectNext("b").thenRequest(1)
                    .expectNext("c").thenRequest(1)
                    .expectNext("d").thenRequest(1)
                    .expectNext("e").thenRequest(1)
                    .expectComplete()
                    .verify();

        await().until(() -> httpRequest.completionFuture().isDone());
    }

    @Test
    public void getCookies() {
        final ArmeriaServerHttpRequest req = request(
                HttpRequest.of(HttpHeaders.of(HttpMethod.POST, "/")
                                          .add(HttpHeaderNames.COOKIE, "a=1;b=2")));

        // Check cached.
        final MultiValueMap<String, HttpCookie> cookie1 = req.getCookies();
        final MultiValueMap<String, HttpCookie> cookie2 = req.getCookies();
        assertThat(cookie1 == cookie2).isTrue();

        assertThat(cookie1.get("a")).containsExactly(new HttpCookie("a", "1"));
        assertThat(cookie1.get("b")).containsExactly(new HttpCookie("b", "2"));
    }

    @Test
    public void cancel() {
        final HttpRequest httpRequest =
                HttpRequest.of(HttpHeaders.of(HttpMethod.POST, "/"),
                               Flux.just("a", "b", "c", "d", "e")
                                   .map(HttpData::ofUtf8));

        final ArmeriaServerHttpRequest req = request(httpRequest);

        assertThat(httpRequest.completionFuture().isDone()).isFalse();

        final Flux<String> body = req.getBody().map(TestUtil::bufferToString);
        StepVerifier.create(body, 1)
                    .expectNext("a").thenRequest(1)
                    .expectNext("b").thenRequest(1)
                    .thenCancel()
                    .verify();

        final CompletableFuture<Void> f = httpRequest.completionFuture();
        assertThat(f.isDone()).isTrue();
        assertThat(f.isCompletedExceptionally()).isTrue();
        assertThatThrownBy(f::get).isInstanceOf(ExecutionException.class)
                                  .hasCauseInstanceOf(CancelledSubscriptionException.class);
    }
}
