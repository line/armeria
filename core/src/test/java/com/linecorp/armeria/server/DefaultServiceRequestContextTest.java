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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.common.DefaultTimeoutController;
import com.linecorp.armeria.internal.common.DefaultTimeoutController.TimeoutTask;
import com.linecorp.armeria.internal.common.TimeoutController;

import io.netty.util.AttributeKey;

class DefaultServiceRequestContextTest {

    @Test
    void requestTimedOut() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/hello");
        final ServiceRequestContext ctx = ServiceRequestContext.builder(request).build();
        assertThat(ctx.isTimedOut()).isFalse();
        assert ctx instanceof DefaultServiceRequestContext;
        final DefaultServiceRequestContext defaultCtx = (DefaultServiceRequestContext) ctx;

        final TimeoutTask timeoutTask = new TimeoutTask() {
            @Override
            public boolean canSchedule() {
                return true;
            }

            @Override
            public void run() {}
        };

        defaultCtx.setRequestTimeoutController(new DefaultTimeoutController(timeoutTask, ctx.eventLoop()));
        defaultCtx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_NOW, 1);

        await().timeout(Duration.ofSeconds(1))
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

        assertThat(derivedCtx.server()).isSameAs(originalCtx.server());
        assertThat(derivedCtx.sessionProtocol()).isSameAs(originalCtx.sessionProtocol());
        assertThat(derivedCtx.service()).isSameAs(originalCtx.service());
        assertThat(derivedCtx.route()).isSameAs(originalCtx.route());
        assertThat(derivedCtx.id()).isSameAs(newId);
        assertThat(derivedCtx.request()).isSameAs(newRequest);

        assertThat(derivedCtx.path()).isEqualTo(originalCtx.path());
        assertThat(derivedCtx.maxRequestLength()).isEqualTo(originalCtx.maxRequestLength());
        assertThat(derivedCtx.requestTimeoutMillis()).isEqualTo(originalCtx.requestTimeoutMillis());
        assertThat(derivedCtx.additionalResponseHeaders().get(HttpHeaderNames.of("my-header#1"))).isNull();
        assertThat(derivedCtx.additionalResponseHeaders().get(HttpHeaderNames.of("my-header#2")))
                .isEqualTo("value#2");
        assertThat(derivedCtx.additionalResponseHeaders().get(HttpHeaderNames.of("my-header#3")))
                .isEqualTo("value#3");
        assertThat(derivedCtx.additionalResponseHeaders().get(HttpHeaderNames.of("my-header#4")))
                .isEqualTo("value#4");
        assertThat(derivedCtx.additionalResponseTrailers().get(HttpHeaderNames.of("my-trailer#1"))).isNull();
        assertThat(derivedCtx.additionalResponseTrailers().get(HttpHeaderNames.of("my-trailer#2")))
                .isEqualTo("value#2");
        assertThat(derivedCtx.additionalResponseTrailers().get(HttpHeaderNames.of("my-trailer#3")))
                .isEqualTo("value#3");
        assertThat(derivedCtx.additionalResponseTrailers().get(HttpHeaderNames.of("my-trailer#4")))
                .isEqualTo("value#4");
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
        final DefaultServiceRequestContext ctx = (DefaultServiceRequestContext) ServiceRequestContext.of(req);
        final TimeoutController timeoutController = mock(TimeoutController.class);
        ctx.setRequestTimeoutController(timeoutController);

        final long oldRequestTimeout1 = ctx.requestTimeoutMillis();
        ctx.setRequestTimeoutMillis(TimeoutMode.EXTEND, 1000);
        assertThat(ctx.requestTimeoutMillis()).isEqualTo(oldRequestTimeout1 + 1000);

        final long oldRequestTimeout2 = ctx.requestTimeoutMillis();
        ctx.setRequestTimeout(TimeoutMode.EXTEND, Duration.ofSeconds(-2));
        assertThat(ctx.requestTimeoutMillis()).isEqualTo(oldRequestTimeout2 - 2000);

        final long oldRequestTimeout3 = ctx.requestTimeoutMillis();
        ctx.setRequestTimeoutMillis(TimeoutMode.EXTEND, 0);
        assertThat(ctx.requestTimeoutMillis()).isEqualTo(oldRequestTimeout3);
    }

    @Test
    void extendRequestTimeoutFromZero() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultServiceRequestContext ctx = (DefaultServiceRequestContext) ServiceRequestContext.of(req);
        final TimeoutController timeoutController = mock(TimeoutController.class);
        ctx.setRequestTimeoutController(timeoutController);

        // This request now has an infinite timeout
        ctx.clearRequestTimeout();

        ctx.setRequestTimeoutMillis(TimeoutMode.EXTEND, 1000);
        assertThat(ctx.requestTimeoutMillis()).isEqualTo(0);

        ctx.setRequestTimeoutMillis(TimeoutMode.EXTEND, -1000);
        assertThat(ctx.requestTimeoutMillis()).isEqualTo(0);
    }

    @Test
    void setRequestTimeoutAfter() throws InterruptedException {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultServiceRequestContext ctx = (DefaultServiceRequestContext) ServiceRequestContext.of(req);
        final long tolerance = 100;

        final TimeoutController timeoutController = mock(TimeoutController.class);
        when(timeoutController.startTimeNanos()).thenReturn(System.nanoTime());
        ctx.setRequestTimeoutController(timeoutController);

        final long passedTimeMillis1 = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - timeoutController.startTimeNanos());
        ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_NOW, 1000);
        assertThat(ctx.requestTimeoutMillis()).isBetween(passedTimeMillis1 + 1000 - tolerance,
                                                         passedTimeMillis1 + 1000 + tolerance);
        Thread.sleep(1000);
        final long passedTimeMillis2 = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - timeoutController.startTimeNanos());
        ctx.setRequestTimeout(TimeoutMode.SET_FROM_NOW, Duration.ofSeconds(2));
        assertThat(ctx.requestTimeoutMillis()).isBetween(passedTimeMillis2 + 2000 - tolerance,
                                                         passedTimeMillis2 + 2000 + tolerance);
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
    void setRequestTimeoutAt() throws InterruptedException {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultServiceRequestContext ctx = (DefaultServiceRequestContext) ServiceRequestContext.of(req);
        final long tolerance = 100;

        final TimeoutController timeoutController = mock(TimeoutController.class);
        when(timeoutController.startTimeNanos()).thenReturn(System.nanoTime());
        ctx.setRequestTimeoutController(timeoutController);

        final long passedTimeMillis1 = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - timeoutController.startTimeNanos());
        ctx.setRequestTimeoutAt(Instant.now().plusSeconds(1));
        assertThat(ctx.requestTimeoutMillis()).isBetween(passedTimeMillis1 + 1000 - tolerance,
                                                         passedTimeMillis1 + 1000 + tolerance);

        Thread.sleep(1000);
        final long passedTimeMillis2 = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - timeoutController.startTimeNanos());
        ctx.setRequestTimeoutAtMillis(Instant.now().plusMillis(1500).toEpochMilli());
        assertThat(ctx.requestTimeoutMillis()).isBetween(passedTimeMillis2 + 1500 - tolerance,
                                                         passedTimeMillis2 + 1500 + tolerance);
    }

    @Test
    void setRequestTimeoutAtWithNonPositive() throws InterruptedException {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultServiceRequestContext ctx = (DefaultServiceRequestContext) ServiceRequestContext.of(req);
        final TimeoutController timeoutController = mock(TimeoutController.class);
        ctx.setRequestTimeoutController(timeoutController);

        ctx.setRequestTimeoutAt(Instant.now().minusSeconds(1));
        verify(timeoutController, timeout(1000)).timeoutNow();
    }

    @Test
    void clearRequestTimeout() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultServiceRequestContext ctx = (DefaultServiceRequestContext) ServiceRequestContext.of(req);
        final TimeoutController timeoutController = mock(TimeoutController.class);
        ctx.setRequestTimeoutController(timeoutController);

        ctx.clearRequestTimeout();
        verify(timeoutController, timeout(1000)).cancelTimeout();
        assertThat(ctx.requestTimeoutMillis()).isEqualTo(0);
    }

    @Test
    void setRequestTimeoutFromStart() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultServiceRequestContext ctx = (DefaultServiceRequestContext) ServiceRequestContext.of(req);

        final TimeoutController timeoutController = mock(TimeoutController.class);
        ctx.setRequestTimeoutController(timeoutController);

        ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_START, 1000);
        assertThat(ctx.requestTimeoutMillis()).isEqualTo(1000);
        ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_START, 2000);
        assertThat(ctx.requestTimeoutMillis()).isEqualTo(2000);
        ctx.setRequestTimeout(TimeoutMode.SET_FROM_START, Duration.ofSeconds(3));
        assertThat(ctx.requestTimeoutMillis()).isEqualTo(3000);
        ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_START, 0);
        assertThat(ctx.requestTimeoutMillis()).isEqualTo(0);
    }

    @Test
    void setRequestTimeoutZero() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultServiceRequestContext ctx = (DefaultServiceRequestContext) ServiceRequestContext.of(req);

        final TimeoutController timeoutController = mock(TimeoutController.class);
        ctx.setRequestTimeoutController(timeoutController);

        ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_START, 0);
        verify(timeoutController, timeout(1000)).cancelTimeout();
        assertThat(ctx.requestTimeoutMillis()).isEqualTo(0);
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
