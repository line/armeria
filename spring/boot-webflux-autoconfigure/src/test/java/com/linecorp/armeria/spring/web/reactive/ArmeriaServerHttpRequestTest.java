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

import org.junit.Test;
import org.springframework.http.HttpCookie;
import org.springframework.util.MultiValueMap;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContextBuilder;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

public class ArmeriaServerHttpRequestTest {

    private static ArmeriaServerHttpRequest request(ServiceRequestContext ctx) {
        return new ArmeriaServerHttpRequest(ctx, ctx.request(), DataBufferFactoryWrapper.DEFAULT);
    }

    @Test
    public void readBodyStream() throws Exception {
        final HttpRequest httpRequest =
                HttpRequest.of(HttpHeaders.of(HttpMethod.POST, "/"),
                               Flux.just("a", "b", "c", "d", "e")
                                   .map(HttpData::ofUtf8));

        final ServiceRequestContext ctx = newRequestContext(httpRequest);
        final ArmeriaServerHttpRequest req = request(ctx);
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
        final HttpRequest httpRequest = HttpRequest.of(HttpHeaders.of(HttpMethod.POST, "/")
                                                                  .add(HttpHeaderNames.COOKIE, "a=1;b=2"));
        final ServiceRequestContext ctx = newRequestContext(httpRequest);
        final ArmeriaServerHttpRequest req = request(ctx);

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

        final ServiceRequestContext ctx = newRequestContext(httpRequest);
        final ArmeriaServerHttpRequest req = request(ctx);

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

    private static ServiceRequestContext newRequestContext(HttpRequest httpRequest) {
        return ServiceRequestContextBuilder.of(httpRequest)
                                           .eventLoop(EventLoopGroups.directEventLoop())
                                           .build();
    }
}
