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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.TimeoutController;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;

class DefaultClientRequestContextTest {

    @Test
    void canBringAttributeInServiceRequestContext() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext serviceContext = ServiceRequestContext.of(req);
        final AttributeKey<String> fooKey = AttributeKey.valueOf(DefaultClientRequestContextTest.class, "foo");
        serviceContext.setAttr(fooKey, "foo");
        try (SafeCloseable ignored = serviceContext.push()) {
            final ClientRequestContext clientContext = ClientRequestContext.of(req);
            assertThat(clientContext.attr(fooKey)).isEqualTo("foo");
            assertThat(clientContext.attrs().hasNext()).isTrue();

            final ClientRequestContext derivedContext = clientContext.newDerivedContext(
                    clientContext.id(), clientContext.request(),
                    clientContext.rpcRequest());
            assertThat(derivedContext.attr(fooKey)).isNotNull();
            // Attributes in serviceContext is not copied to clientContext when derived.

            final AttributeKey<String> barKey = AttributeKey.valueOf(DefaultClientRequestContextTest.class,
                                                                     "bar");
            clientContext.setAttr(barKey, "bar");
            assertThat(serviceContext.attr(barKey)).isNull();
        }
    }

    @Test
    void attrsDoNotIterateRootWhenKeyIsSame() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext serviceContext = ServiceRequestContext.of(req);
        try (SafeCloseable ignored = serviceContext.push()) {
            final ClientRequestContext clientContext = ClientRequestContext.of(req);
            final AttributeKey<String> fooKey = AttributeKey.valueOf(DefaultClientRequestContextTest.class,
                                                                     "foo");
            clientContext.setAttr(fooKey, "foo");
            serviceContext.setAttr(fooKey, "bar");
            final Iterator<Entry<AttributeKey<?>, Object>> attrs = clientContext.attrs();
            assertThat(attrs.next().getValue()).isEqualTo("foo");
            assertThat(attrs.hasNext()).isFalse();
        }
    }

    @Test
    void deriveContext() {
        final DefaultClientRequestContext originalCtx = newContext();

        setAdditionalHeaders(originalCtx);

        final AttributeKey<String> foo = AttributeKey.valueOf(DefaultClientRequestContextTest.class, "foo");
        originalCtx.setAttr(foo, "foo");

        final RequestId newId = RequestId.random();
        final HttpRequest newRequest = HttpRequest.of(RequestHeaders.of(
                HttpMethod.POST, "/foo",
                HttpHeaderNames.SCHEME, "http",
                HttpHeaderNames.AUTHORITY, "example.com:8080",
                "foo", "bar"));
        final ClientRequestContext derivedCtx = originalCtx.newDerivedContext(newId, newRequest, null);
        assertThat(derivedCtx.endpoint()).isSameAs(originalCtx.endpoint());
        assertThat(derivedCtx.sessionProtocol()).isSameAs(originalCtx.sessionProtocol());
        assertThat(derivedCtx.method()).isSameAs(originalCtx.method());
        assertThat(derivedCtx.options()).isSameAs(originalCtx.options());
        assertThat(derivedCtx.id()).isSameAs(newId);
        assertThat(derivedCtx.request()).isSameAs(newRequest);

        assertThat(derivedCtx.path()).isEqualTo(originalCtx.path());
        assertThat(derivedCtx.maxResponseLength()).isEqualTo(originalCtx.maxResponseLength());
        assertThat(derivedCtx.responseTimeoutMillis()).isEqualTo(originalCtx.responseTimeoutMillis());
        assertThat(derivedCtx.writeTimeoutMillis()).isEqualTo(originalCtx.writeTimeoutMillis());
        assertThat(derivedCtx.additionalRequestHeaders().get(HttpHeaderNames.of("my-header#1"))).isNull();
        assertThat(derivedCtx.additionalRequestHeaders().get(HttpHeaderNames.of("my-header#2")))
                .isEqualTo("value#2");
        assertThat(derivedCtx.additionalRequestHeaders().get(HttpHeaderNames.of("my-header#3")))
                .isEqualTo("value#3");
        assertThat(derivedCtx.additionalRequestHeaders().get(HttpHeaderNames.of("my-header#4")))
                .isEqualTo("value#4");
        // the attribute is derived as well
        assertThat(derivedCtx.attr(foo)).isEqualTo("foo");

        // log is different
        assertThat(derivedCtx.log()).isNotSameAs(originalCtx.log());

        final AttributeKey<String> bar = AttributeKey.valueOf(DefaultClientRequestContextTest.class, "bar");
        originalCtx.setAttr(bar, "bar");

        // the Attribute added to the original context after creation is not propagated to the derived context
        assertThat(derivedCtx.attr(bar)).isEqualTo(null);
    }

    @Test
    void derivedContextMustNotCallCustomizers() {
        final AtomicInteger counter = new AtomicInteger();
        try (SafeCloseable unused = Clients.withContextCustomizer(unused2 -> counter.incrementAndGet())) {
            final DefaultClientRequestContext ctx = newContext();
            assertThat(counter).hasValue(1);

            // Create a derived context, which should never call customizers or captor.
            try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
                ctx.newDerivedContext(RequestId.random(), ctx.request(), null, ctx.endpoint());
                assertThat(counter).hasValue(1);
                assertThatThrownBy(ctxCaptor::get).isInstanceOf(NoSuchElementException.class)
                                                  .hasMessageContaining("no request was made");

                assertThat(ctxCaptor.getOrNull()).isNull();
            }
        }

        // Thread-local state must be cleaned up.
        assertThat(ClientThreadLocalState.get()).isNull();
    }

    @Test
    void contextCaptorMustBeCleanedUp() {
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            assertThat(ClientThreadLocalState.get()).isNotNull();
            final DefaultClientRequestContext ctx = newContext();
            assertThat(ctxCaptor.get()).isSameAs(ctx);
        }

        // Thread-local state must be cleaned up.
        assertThat(ClientThreadLocalState.get()).isNull();
    }

    @Test
    void nestedContextCaptors() {
        try (ClientRequestContextCaptor ctxCaptor1 = Clients.newContextCaptor()) {
            final DefaultClientRequestContext ctx1 = newContext();
            assertThat(ctxCaptor1.getAll()).containsExactly(ctx1);

            final ClientRequestContext ctx2;
            final ClientRequestContext ctx3;
            ClientRequestContextCaptor ctxCaptor2 = null;
            try {
                ctxCaptor2 = Clients.newContextCaptor();
                ctx2 = newContext();
                // The context captured by the second captor is also captured by the first captor.
                assertThat(ctxCaptor1.getAll()).containsExactly(ctx1, ctx2);
                assertThat(ctxCaptor2.getAll()).containsExactly(ctx2);
                try (ClientRequestContextCaptor ctxCaptor3 = Clients.newContextCaptor()) {
                    ctx3 = newContext();
                    assertThat(ctxCaptor1.getAll()).containsExactly(ctx1, ctx2, ctx3);
                    assertThat(ctxCaptor2.getAll()).containsExactly(ctx2, ctx3);
                }
            } finally {
                if (ctxCaptor2 != null) {
                    ctxCaptor2.close();
                }
            }

            final DefaultClientRequestContext ctx4 = newContext();
            assertThat(ctxCaptor1.getAll()).containsExactly(ctx1, ctx2, ctx3, ctx4);
            assertThat(ctxCaptor2.getAll()).containsExactly(ctx2, ctx3);
        }

        // Thread-local state must be cleaned up.
        assertThat(ClientThreadLocalState.get()).isNull();
    }

    private static DefaultClientRequestContext newContext() {
        final DefaultClientRequestContext ctx = new DefaultClientRequestContext(
                mock(EventLoop.class), NoopMeterRegistry.get(), SessionProtocol.H2C,
                RequestId.random(), HttpMethod.POST, "/foo", null, null,
                ClientOptions.of(),
                HttpRequest.of(RequestHeaders.of(
                        HttpMethod.POST, "/foo",
                        HttpHeaderNames.SCHEME, "http",
                        HttpHeaderNames.AUTHORITY, "example.com:8080")),
                null, System.nanoTime(), SystemInfo.currentTimeMicros());
        ctx.init(Endpoint.of("example.com", 8080));
        return ctx;
    }

    @Test
    void extendResponseTimeout() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext ctx = (DefaultClientRequestContext) ClientRequestContext.of(req);
        final TimeoutController timeoutController = mock(TimeoutController.class);
        ctx.setResponseTimeoutController(timeoutController);

        final long oldResponseTimeout1 = ctx.responseTimeoutMillis();
        ctx.extendResponseTimeoutMillis(1000);
        assertThat(ctx.responseTimeoutMillis()).isEqualTo(oldResponseTimeout1 + 1000);

        final long oldResponseTimeout2 = ctx.responseTimeoutMillis();
        ctx.extendResponseTimeout(Duration.ofSeconds(-2));
        assertThat(ctx.responseTimeoutMillis()).isEqualTo(oldResponseTimeout2 - 2000);

        final long oldResponseTimeout3 = ctx.responseTimeoutMillis();
        ctx.extendResponseTimeoutMillis(0);
        assertThat(ctx.responseTimeoutMillis()).isEqualTo(oldResponseTimeout3);
    }

    @Test
    void extendResponseTimeoutFromZero() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext ctx = (DefaultClientRequestContext) ClientRequestContext.of(req);
        final TimeoutController timeoutController = mock(TimeoutController.class);
        ctx.setResponseTimeoutController(timeoutController);

        // This response now has an infinite timeout
        ctx.clearResponseTimeout();

        ctx.extendResponseTimeoutMillis(1000);
        assertThat(ctx.responseTimeoutMillis()).isEqualTo(0);

        ctx.extendResponseTimeoutMillis(-1000);
        assertThat(ctx.responseTimeoutMillis()).isEqualTo(0);
    }

    @Test
    void setResponseTimeoutAfter() throws InterruptedException {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext ctx = (DefaultClientRequestContext) ClientRequestContext.of(req);
        final long tolerance = 30;

        final TimeoutController timeoutController = mock(TimeoutController.class);
        when(timeoutController.startTimeNanos()).thenReturn(System.nanoTime());
        ctx.setResponseTimeoutController(timeoutController);

        final long passedTimeMillis1 = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - timeoutController.startTimeNanos());
        ctx.setResponseTimeoutAfterMillis(1000);
        assertThat(ctx.responseTimeoutMillis()).isBetween(passedTimeMillis1 + 1000 - tolerance,
                                                          passedTimeMillis1 + 1000 + tolerance);
        Thread.sleep(1000);
        final long passedTimeMillis2 = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - timeoutController.startTimeNanos());
        ctx.setResponseTimeoutAfter(Duration.ofSeconds(2));
        assertThat(ctx.responseTimeoutMillis()).isBetween(passedTimeMillis2 + 2000 - tolerance,
                                                          passedTimeMillis2 + 2000 + tolerance);
    }

    @Test
    void setResponseTimeoutAfterWithNonPositive() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext ctx = (DefaultClientRequestContext) ClientRequestContext.of(req);
        assertThatThrownBy(() -> ctx.setResponseTimeoutAfterMillis(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("(expected: > 0)");

        assertThatThrownBy(() -> ctx.setResponseTimeoutAfterMillis(-10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("(expected: > 0)");
    }

    @Test
    void setResponseTimeoutAt() throws InterruptedException {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext ctx = (DefaultClientRequestContext) ClientRequestContext.of(req);
        final long tolerance = 30;

        final TimeoutController timeoutController = mock(TimeoutController.class);
        when(timeoutController.startTimeNanos()).thenReturn(System.nanoTime());
        ctx.setResponseTimeoutController(timeoutController);

        final long passedTimeMillis1 = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - timeoutController.startTimeNanos());
        ctx.setResponseTimeoutAt(Instant.now().plusSeconds(1));
        assertThat(ctx.responseTimeoutMillis()).isBetween(passedTimeMillis1 + 1000 - tolerance,
                                                          passedTimeMillis1 + 1000 + tolerance);

        Thread.sleep(1000);
        final long passedTimeMillis2 = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - timeoutController.startTimeNanos());
        ctx.setResponseTimeoutAt(Instant.now().plusMillis(1500));
        assertThat(ctx.responseTimeoutMillis()).isBetween(passedTimeMillis2 + 1500 - tolerance,
                                                          passedTimeMillis2 + 1500 + tolerance);
    }

    @Test
    void setResponseTimeoutAtWithNonPositive() throws InterruptedException {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext ctx = (DefaultClientRequestContext) ClientRequestContext.of(req);
        final TimeoutController timeoutController = mock(TimeoutController.class);
        ctx.setResponseTimeoutController(timeoutController);

        ctx.setResponseTimeoutAt(Instant.now().minusSeconds(1));
        verify(timeoutController, timeout(Duration.ofSeconds(1))).timeoutNow();
    }

    @Test
    void clearResponseTimeout() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext ctx = (DefaultClientRequestContext) ClientRequestContext.of(req);
        final TimeoutController timeoutController = mock(TimeoutController.class);
        ctx.setResponseTimeoutController(timeoutController);

        ctx.clearResponseTimeout();
        verify(timeoutController, timeout(Duration.ofSeconds(1)))
                .cancelTimeout();
        assertThat(ctx.responseTimeoutMillis()).isEqualTo(0);
    }

    @Test
    void clearResponseTimeoutWithPendingTask() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext ctx = (DefaultClientRequestContext) ClientRequestContext.of(req);
        ctx.clearResponseTimeout();
        final TimeoutController timeoutController = mock(TimeoutController.class);
        ctx.setResponseTimeoutController(timeoutController);

        verify(timeoutController, timeout(Duration.ofSeconds(1)))
                .cancelTimeout();
        assertThat(ctx.responseTimeoutMillis()).isEqualTo(0);
    }

    @Test
    void setResponseTimeout() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext ctx = (DefaultClientRequestContext) ClientRequestContext.of(req);

        final TimeoutController timeoutController = mock(TimeoutController.class);
        ctx.setResponseTimeoutController(timeoutController);

        ctx.setResponseTimeoutMillis(1000);
        assertThat(ctx.responseTimeoutMillis()).isEqualTo(1000);
        ctx.setResponseTimeoutMillis(2000);
        assertThat(ctx.responseTimeoutMillis()).isEqualTo(2000);
        ctx.setResponseTimeoutMillis(0);
        assertThat(ctx.responseTimeoutMillis()).isEqualTo(0);
    }

    @Test
    void setResponseTimeoutZero() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext ctx = (DefaultClientRequestContext) ClientRequestContext.of(req);

        final TimeoutController timeoutController = mock(TimeoutController.class);
        ctx.setResponseTimeoutController(timeoutController);

        ctx.setResponseTimeoutMillis(0);
        verify(timeoutController, timeout(Duration.ofSeconds(1))).cancelTimeout();
        assertThat(ctx.responseTimeoutMillis()).isEqualTo(0);
    }

    private static void setAdditionalHeaders(ClientRequestContext originalCtx) {
        final HttpHeaders headers1 = HttpHeaders.of(HttpHeaderNames.of("my-header#1"), "value#1");
        originalCtx.setAdditionalRequestHeaders(headers1);
        originalCtx.setAdditionalRequestHeader(HttpHeaderNames.of("my-header#2"), "value#2");

        final HttpHeaders headers2 = HttpHeaders.of(HttpHeaderNames.of("my-header#3"), "value#3");
        originalCtx.addAdditionalRequestHeaders(headers2);
        originalCtx.addAdditionalRequestHeader(HttpHeaderNames.of("my-header#4"), "value#4");
        // Remove the first one.
        originalCtx.removeAdditionalRequestHeader(HttpHeaderNames.of("my-header#1"));
    }
}
