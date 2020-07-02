/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.util.TimeoutMode;

import io.netty.util.AttributeKey;

class DefaultServiceRequestContextTest {

    AtomicBoolean finished;

    @BeforeEach
    void setUp() {
        finished = new AtomicBoolean();
    }

    @Test
    void requestTimedOut() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/hello");
        final ServiceRequestContext ctx = ServiceRequestContext.builder(request).build();
        assertThat(ctx.isTimedOut()).isFalse();
        assert ctx instanceof DefaultServiceRequestContext;
        final DefaultServiceRequestContext defaultCtx = (DefaultServiceRequestContext) ctx;
        defaultCtx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_NOW, 1000);

        await().timeout(Duration.ofSeconds(3))
               .untilAsserted(() -> assertThat(ctx.isTimedOut()).isTrue());
    }

    @Test
    void deriveContext() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/hello");
        final ServiceRequestContext originalCtx = ServiceRequestContext.builder(request).build();

        mutateAdditionalHeaders(originalCtx);
        mutateAdditionalTrailers(originalCtx);

        final AttributeKey<String> foo = AttributeKey.valueOf(DefaultServiceRequestContextTest.class, "foo");
        originalCtx.setAttr(foo, "foo");

        final RequestId newId = RequestId.random();
        final HttpRequest newRequest = HttpRequest.of(HttpMethod.GET, "/derived/hello");
        final ServiceRequestContext derivedCtx = originalCtx.newDerivedContext(newId, newRequest, null);

        // A ServiceRequestContext must always have itself as its root.
        assertThat(derivedCtx.root()).isSameAs(derivedCtx);

        assertThat(derivedCtx.config().server()).isSameAs(originalCtx.config().server());
        assertThat(derivedCtx.sessionProtocol()).isSameAs(originalCtx.sessionProtocol());
        assertThat(derivedCtx.config().service()).isSameAs(originalCtx.config().service());
        assertThat(derivedCtx.config().route()).isSameAs(originalCtx.config().route());
        assertThat(derivedCtx.id()).isSameAs(newId);
        assertThat(derivedCtx.request()).isSameAs(newRequest);

        assertThat(derivedCtx.path()).isEqualTo(originalCtx.path());
        assertThat(derivedCtx.maxRequestLength()).isEqualTo(originalCtx.maxRequestLength());
        assertThat(derivedCtx.requestTimeoutMillis()).isEqualTo(originalCtx.requestTimeoutMillis());
        assertThat(derivedCtx.additionalResponseHeaders()).isSameAs(originalCtx.additionalResponseHeaders());
        assertThat(derivedCtx.additionalResponseTrailers()).isSameAs(originalCtx.additionalResponseTrailers());
        // the attribute is derived as well
        assertThat(derivedCtx.attr(foo)).isEqualTo("foo");

        // log is different
        assertThat(derivedCtx.log()).isNotSameAs(originalCtx.log());

        final AttributeKey<String> bar = AttributeKey.valueOf(DefaultServiceRequestContextTest.class, "bar");
        originalCtx.setAttr(bar, "bar");

        // the Attribute added to the original context after creation is not propagated to the derived context
        assertThat(derivedCtx.attr(bar)).isEqualTo(null);
    }

    @Test
    void extendRequestTimeout() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        ctx.eventLoop().execute(() -> {
            ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_NOW, 1000);

            final long oldRequestTimeout1 = ctx.requestTimeoutMillis();
            ctx.setRequestTimeoutMillis(TimeoutMode.EXTEND, 1000);
            assertThat(ctx.requestTimeoutMillis()).isEqualTo(oldRequestTimeout1 + 1000);

            final long oldRequestTimeout2 = ctx.requestTimeoutMillis();
            ctx.setRequestTimeout(TimeoutMode.EXTEND, Duration.ofSeconds(-2));
            assertThat(ctx.requestTimeoutMillis()).isEqualTo(oldRequestTimeout2 - 2000);

            final long oldRequestTimeout3 = ctx.requestTimeoutMillis();
            ctx.setRequestTimeoutMillis(TimeoutMode.EXTEND, 0);
            assertThat(ctx.requestTimeoutMillis()).isEqualTo(oldRequestTimeout3);

            finished.set(true);
        });

        await().untilTrue(finished);
    }

    @Test
    void extendRequestTimeoutFromZero() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        ctx.eventLoop().execute(() -> {
            // This request now has an infinite timeout
            ctx.clearRequestTimeout();

            ctx.setRequestTimeoutMillis(TimeoutMode.EXTEND, 1000);
            assertThat(ctx.requestTimeoutMillis()).isEqualTo(0);

            ctx.setRequestTimeoutMillis(TimeoutMode.EXTEND, -1000);
            assertThat(ctx.requestTimeoutMillis()).isEqualTo(0);
            finished.set(true);
        });

        await().untilTrue(finished);
    }

    @Test
    void setRequestTimeoutAfter() throws InterruptedException {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultServiceRequestContext ctx = (DefaultServiceRequestContext) ServiceRequestContext.of(req);
        final long tolerance = 500;

        ctx.eventLoop().execute(() -> {
            ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_NOW, 1000);
            final long oldRequestTimeoutMillis = ctx.requestTimeoutMillis();
            ctx.setRequestTimeout(TimeoutMode.SET_FROM_NOW, Duration.ofSeconds(2));
            assertThat(ctx.requestTimeoutMillis()).isBetween(oldRequestTimeoutMillis + 1000 - tolerance,
                                                             oldRequestTimeoutMillis + 1000 + tolerance);
            finished.set(true);
        });
        await().untilTrue(finished);
    }

    @Test
    void setRequestTimeoutAfterWithNonPositive() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultServiceRequestContext ctx = (DefaultServiceRequestContext) ServiceRequestContext.of(req);
        assertThatThrownBy(() -> ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_NOW, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("(expected: > 0)");

        assertThatThrownBy(() -> ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_NOW, -10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("(expected: > 0)");
    }

    @Test
    void clearRequestTimeout() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultServiceRequestContext ctx = (DefaultServiceRequestContext) ServiceRequestContext.of(req);

        ctx.eventLoop().execute(() -> {
            ctx.setRequestTimeoutMillis(2000);
            assertThat(ctx.requestTimeoutMillis()).isEqualTo(2000);
            ctx.clearRequestTimeout();
            assertThat(ctx.requestTimeoutMillis()).isEqualTo(0);
            finished.set(true);
        });

        await().untilTrue(finished);
    }

    @Test
    void setRequestTimeoutFromStart() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        ctx.eventLoop().execute(() -> {
            ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_START, 1000);
            assertThat(ctx.requestTimeoutMillis()).isEqualTo(1000);
            ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_START, 2000);
            assertThat(ctx.requestTimeoutMillis()).isEqualTo(2000);
            ctx.setRequestTimeout(TimeoutMode.SET_FROM_START, Duration.ofSeconds(3));
            assertThat(ctx.requestTimeoutMillis()).isEqualTo(3000);
            ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_START, 0);
            assertThat(ctx.requestTimeoutMillis()).isEqualTo(0);
            finished.set(true);
        });

        await().untilTrue(finished);
    }

    @Test
    void setRequestTimeoutZero() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_START, 0);
        await().untilAsserted(() -> {
            assertThat(ctx.requestTimeoutMillis()).isEqualTo(0);
        });
    }

    private static void mutateAdditionalHeaders(ServiceRequestContext originalCtx) {
        originalCtx.mutateAdditionalResponseHeaders(
                mutator -> mutator.add(HttpHeaderNames.of("my-header#2"), "value#2"));

        final HttpHeaders headers2 = HttpHeaders.of(HttpHeaderNames.of("my-header#3"), "value#3");
        originalCtx.mutateAdditionalResponseHeaders(mutator -> mutator.add(headers2));
        originalCtx.mutateAdditionalResponseHeaders(
                mutator -> mutator.add(HttpHeaderNames.of("my-header#4"), "value#4"));
    }

    private static void mutateAdditionalTrailers(ServiceRequestContext originalCtx) {
        originalCtx.mutateAdditionalResponseTrailers(
                mutator -> mutator.add(HttpHeaderNames.of("my-trailer#2"), "value#2"));

        final HttpHeaders trailers2 = HttpHeaders.of(HttpHeaderNames.of("my-trailer#3"), "value#3");
        originalCtx.mutateAdditionalResponseTrailers(mutator -> mutator.add(trailers2));
        originalCtx.mutateAdditionalResponseTrailers(
                mutator -> mutator.add(HttpHeaderNames.of("my-trailer#4"), "value#4"));
    }
}
