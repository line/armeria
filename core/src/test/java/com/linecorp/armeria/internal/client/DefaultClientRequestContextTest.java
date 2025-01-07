/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.common.CancellationScheduler;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;

class DefaultClientRequestContextTest {

    AtomicBoolean finished;

    @BeforeEach
    void setUp() {
        finished = new AtomicBoolean();
    }

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
                    clientContext.rpcRequest(), clientContext.endpoint());
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

        mutateAdditionalHeaders(originalCtx);

        final AttributeKey<String> foo = AttributeKey.valueOf(DefaultClientRequestContextTest.class, "foo");
        originalCtx.setAttr(foo, "foo");

        final RequestId newId = RequestId.random();
        final HttpRequest newRequest = HttpRequest.of(RequestHeaders.of(
                HttpMethod.POST, "/foo",
                HttpHeaderNames.SCHEME, "http",
                HttpHeaderNames.AUTHORITY, "example.com:8080",
                "foo", "bar"));
        final ClientRequestContext derivedCtx = originalCtx.newDerivedContext(newId, newRequest, null,
                                                                              originalCtx.endpoint());
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
        assertThat(derivedCtx.additionalRequestHeaders()).isSameAs(originalCtx.additionalRequestHeaders());
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
        try (SafeCloseable ignored = Clients.withContextCustomizer(unused2 -> counter.incrementAndGet())) {
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
                try (ClientRequestContextCaptor ignored = Clients.newContextCaptor()) {
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

    @Test
    void testAuthorityOverridden() {
        final HttpRequest request1 = HttpRequest.of(RequestHeaders.of(
                HttpMethod.POST, "/foo",
                HttpHeaderNames.SCHEME, "http"));
        final DefaultClientRequestContext ctx = newContext(ClientOptions.of(), request1,
                                                           Endpoint.of("endpoint.com", 8080));
        assertThat(ctx.authority()).isNull();
        assertThat(ctx.uri().toString()).isEqualTo("http:/foo");
        assertThat(ctx.uri()).hasScheme("http").hasAuthority(null).hasPath("/foo");

        ctx.init();
        assertThat(ctx.authority()).isEqualTo("endpoint.com:8080");
        assertThat(ctx.uri().toString()).isEqualTo("http://endpoint.com:8080/foo");

        final HttpRequest request2 = HttpRequest.of(RequestHeaders.of(
                HttpMethod.POST, "/bar",
                HttpHeaderNames.SCHEME, "http",
                HttpHeaderNames.AUTHORITY, "request.com"));
        ctx.updateRequest(request2);
        assertThat(ctx.authority()).isEqualTo("request.com");
        assertThat(ctx.uri().toString()).isEqualTo("http://request.com/bar");

        ctx.addAdditionalRequestHeader(HttpHeaderNames.AUTHORITY, "additional.com");
        assertThat(ctx.authority()).isEqualTo("additional.com");
        assertThat(ctx.uri().toString()).isEqualTo("http://additional.com/bar");
    }

    @Test
    void testDefaultAuthorityOverridesInternal() {
        final HttpRequest request1 = HttpRequest.of(RequestHeaders.of(
                HttpMethod.POST, "/foo",
                HttpHeaderNames.SCHEME, "http"));
        final ClientOptions clientOptions = ClientOptions.builder()
                                                         .addHeader(HttpHeaderNames.AUTHORITY, "default.com")
                                                         .build();
        final DefaultClientRequestContext ctx = newContext(clientOptions, request1,
                                                           Endpoint.of("example.com", 8080));
        ctx.init();
        assertThat(ctx.authority()).isEqualTo("default.com");
        assertThat(ctx.uri().toString()).isEqualTo("http://default.com/foo");
    }

    @Test
    void requestUpdateAllComponents() {
        final DefaultClientRequestContext ctx = newContext();
        assertThat(ctx.authority()).isEqualTo("example.com:8080");
        assertThat(ctx.uri().toString()).isEqualTo("http://example.com:8080/foo");

        final HttpRequest request = HttpRequest.of(RequestHeaders.of(
                HttpMethod.POST, "/a/b/c?q1=p1&q2=p2#fragment1",
                HttpHeaderNames.SCHEME, "http",
                HttpHeaderNames.AUTHORITY, "request.com"));
        ctx.updateRequest(request);
        assertThat(ctx.authority()).isEqualTo("request.com");
        assertThat(ctx.uri().toString()).isEqualTo("http://request.com/a/b/c?q1=p1&q2=p2#fragment1");
        assertThat(ctx.endpoint().authority()).isEqualTo("example.com:8080");
    }

    @Test
    void uriWithOnlySchemePath() {
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(
                HttpMethod.POST, "/",
                HttpHeaderNames.SCHEME, "http"));
        final DefaultClientRequestContext ctx = newContext(ClientOptions.of(), request,
                                                           EndpointGroup.of());
        ctx.updateRequest(request);
        assertThat(ctx.uri().toString()).isEqualTo("http:/");
    }

    private static DefaultClientRequestContext newContext() {
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(
                HttpMethod.POST, "/foo",
                HttpHeaderNames.SCHEME, "http",
                HttpHeaderNames.AUTHORITY, "example.com:8080"));
        final DefaultClientRequestContext ctx = newContext(ClientOptions.of(), request,
                                                           Endpoint.of("example.com", 8080));
        ctx.init();
        return ctx;
    }

    private static DefaultClientRequestContext newContext(ClientOptions clientOptions,
                                                          HttpRequest httpRequest,
                                                          EndpointGroup endpointGroup) {
        final RequestTarget reqTarget = RequestTarget.forClient(httpRequest.path());
        assertThat(reqTarget).isNotNull();

        return new DefaultClientRequestContext(
                mock(EventLoop.class), NoopMeterRegistry.get(), SessionProtocol.H2C,
                RequestId.random(), HttpMethod.POST, reqTarget, endpointGroup, clientOptions, httpRequest,
                null, RequestOptions.of(), CancellationScheduler.ofClient(0), System.nanoTime(),
                SystemInfo.currentTimeMicros());
    }

    @Test
    void extendResponseTimeout() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(req);

        ctx.eventLoop().execute(() -> {
            ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW, 1000);
            final long oldResponseTimeout1 = ctx.responseTimeoutMillis();
            ctx.setResponseTimeoutMillis(TimeoutMode.EXTEND, 1000);
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(oldResponseTimeout1 + 1000);

            final long oldResponseTimeout2 = ctx.responseTimeoutMillis();
            ctx.setResponseTimeout(TimeoutMode.EXTEND, Duration.ofSeconds(-2));
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(oldResponseTimeout2 - 2000);

            final long oldResponseTimeout3 = ctx.responseTimeoutMillis();
            ctx.setResponseTimeoutMillis(TimeoutMode.EXTEND, 0);
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(oldResponseTimeout3);
            finished.set(true);
        });
        await().untilTrue(finished);
    }

    @Test
    void extendResponseTimeoutFromZero() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(req);

        ctx.eventLoop().execute(() -> {
            // This response now has an infinite timeout
            ctx.clearResponseTimeout();

            ctx.setResponseTimeoutMillis(TimeoutMode.EXTEND, 1000);
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(0);

            ctx.setResponseTimeoutMillis(TimeoutMode.EXTEND, -1000);
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(0);
            finished.set(true);
        });
        await().untilTrue(finished);
    }

    @Test
    void setResponseTimeoutAfter() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext ctx = (DefaultClientRequestContext) ClientRequestContext.of(req);
        final long tolerance = 500;

        ctx.eventLoop().execute(() -> {
            ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW, 1000);
            final long oldResponseTimeoutMillis = ctx.responseTimeoutMillis();
            ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW, 2000);
            assertThat(ctx.responseTimeoutMillis()).isBetween(oldResponseTimeoutMillis + 1000 - tolerance,
                                                              oldResponseTimeoutMillis + 1000 + tolerance);
            finished.set(true);
        });

        await().untilTrue(finished);
    }

    @Test
    void setResponseTimeoutAfterWithNonPositive() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(req);
        assertThatThrownBy(() -> ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("(expected: > 0)");

        assertThatThrownBy(() -> ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW, -10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("(expected: > 0)");
    }

    @Test
    void clearResponseTimeout() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(req);

        ctx.eventLoop().execute(() -> {
            ctx.setResponseTimeout(Duration.ofSeconds(1));
            ctx.clearResponseTimeout();
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(0);
            finished.set(true);
        });

        await().untilTrue(finished);
    }

    @Test
    void setResponseTimeoutFromStart() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(req);

        ctx.eventLoop().execute(() -> {
            ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_START, 1000);
            assertThat(ctx.responseTimeoutMillis()).isCloseTo(1000, Offset.offset(200L));
            ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_START, 2000);
            assertThat(ctx.responseTimeoutMillis()).isCloseTo(2000, Offset.offset(200L));
            ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_START, 0);
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(0);
            finished.set(true);
        });
        await().untilTrue(finished);
    }

    @Test
    void testToStringSlow() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext ctxWithChannel =
                (DefaultClientRequestContext) ClientRequestContext.of(req);
        final DefaultClientRequestContext ctxWithNoChannel = newContext();

        assertThat(ctxWithNoChannel.channel()).isNull();
        final String strWithNoChannel = ctxWithNoChannel.toString();
        assertThat(strWithNoChannel).doesNotContain("chanId=");
        assertThat(ctxWithNoChannel.toString()).isSameAs(strWithNoChannel);

        ctxWithNoChannel.logBuilder().session(ctxWithChannel.channel(), ctxWithChannel.sessionProtocol(), null);
        final String strWithChannel = ctxWithNoChannel.toString();
        assertThat(strWithChannel).contains("chanId=");
        assertThat(ctxWithNoChannel.toString()).isSameAs(strWithChannel);

        assertThat(ctxWithNoChannel.log().parent()).isNull();
        final String strWithNoParentLog = ctxWithNoChannel.toString();
        assertThat(strWithNoParentLog).doesNotContain("preqId=");
        assertThat(ctxWithNoChannel.toString()).isSameAs(strWithNoParentLog);

        ctxWithChannel.logBuilder().addChild(ctxWithNoChannel.log());
        final String strWithParentLog = ctxWithNoChannel.toString();
        assertThat(strWithParentLog).contains("preqId=");
        assertThat(ctxWithNoChannel.toString()).isSameAs(strWithParentLog);
    }

    private static void mutateAdditionalHeaders(ClientRequestContext originalCtx) {
        final HttpHeaders headers1 = HttpHeaders.of(HttpHeaderNames.of("my-header#1"), "value#1");
        originalCtx.mutateAdditionalRequestHeaders(mutator -> mutator.add(headers1));
        originalCtx.mutateAdditionalRequestHeaders(
                mutator -> mutator.add(HttpHeaderNames.of("my-header#2"), "value#2"));

        final HttpHeaders headers2 = HttpHeaders.of(HttpHeaderNames.of("my-header#3"), "value#3");
        originalCtx.mutateAdditionalRequestHeaders(mutator -> mutator.add(headers2));
        originalCtx.mutateAdditionalRequestHeaders(
                mutator -> mutator.add(HttpHeaderNames.of("my-header#4"), "value#4"));
        // Remove the first one.
        originalCtx.mutateAdditionalRequestHeaders(
                mutator -> mutator.remove(HttpHeaderNames.of("my-header#1")));
    }
}
