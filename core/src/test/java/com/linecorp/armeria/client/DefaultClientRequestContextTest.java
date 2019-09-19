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
import static org.mockito.Mockito.mock;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;

import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;

class DefaultClientRequestContextTest {

    @Test
    void uri() {
        final HttpRequest request =
                HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/foo",
                                                 HttpHeaderNames.AUTHORITY, "example.com:8080"));
        final ClientRequestContext ctx = ClientRequestContextBuilder.of(request)
                                                                    .sessionProtocol(SessionProtocol.H2C)
                                                                    .build();
        assertThat(ctx.uri()).isEqualTo(URI.create("h2c://example.com:8080/foo"))
                             .isEqualTo(ctx.uri(false));
        assertThat(ctx.uri(true)).isEqualTo(URI.create("http://example.com:8080/foo"));
    }

    @Test
    void uriWithQuery() {
        final HttpRequest request =
                HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/foo?bar=baz",
                                                 HttpHeaderNames.AUTHORITY, "127.0.0.1"));
        final ClientRequestContext ctx = ClientRequestContextBuilder.of(request)
                                                                    .sessionProtocol(SessionProtocol.H2C)
                                                                    .build();

        assertThat(ctx.uri()).isEqualTo(URI.create("h2c://127.0.0.1/foo?bar=baz"))
                             .isEqualTo(ctx.uri(false));
        assertThat(ctx.uri(true)).isEqualTo(URI.create("http://127.0.0.1/foo?bar=baz"));
    }

    @Test
    void uriWithFragment() {
        final RpcRequest request = RpcRequest.of(Object.class, "bar", ImmutableList.of());
        final ClientRequestContext ctx =
                ClientRequestContextBuilder.of(request, URI.create("h2c://[::1]:8080/foo#fragment"))
                                           .sessionProtocol(SessionProtocol.H2C)
                                           .build();

        assertThat(ctx.uri()).isEqualTo(URI.create("h2c://[::1]:8080/foo#fragment"))
                             .isEqualTo(ctx.uri(false));
        assertThat(ctx.uri(true)).isEqualTo(URI.create("http://[::1]:8080/foo#fragment"));
    }

    @Test
    void uriCache() {
        final HttpRequest request =
                HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/foo",
                                                 HttpHeaderNames.AUTHORITY, "example.com:8080"));
        final ClientRequestContext ctx = ClientRequestContextBuilder.of(request)
                                                                    .sessionProtocol(SessionProtocol.H2C)
                                                                    .build();
        assertThat(ctx.uri(true)).isSameAs(ctx.uri(true));
        assertThat(ctx.uri(false)).isSameAs(ctx.uri(false));
        assertThat(ctx.uri(true)).isNotSameAs(ctx.uri(false));
    }

    @Test
    void deriveContext() {
        final DefaultClientRequestContext originalCtx = new DefaultClientRequestContext(
                mock(EventLoop.class), NoopMeterRegistry.get(),
                Scheme.of(SerializationFormat.NONE, SessionProtocol.H2C),
                HttpMethod.POST, "/foo", null, null,
                ClientOptions.DEFAULT, mock(Request.class));
        originalCtx.init(Endpoint.of("example.com", 8080));

        setAdditionalHeaders(originalCtx);

        final AttributeKey<String> foo = AttributeKey.valueOf(DefaultClientRequestContextTest.class, "foo");
        originalCtx.attr(foo).set("foo");

        final Request newRequest = mock(Request.class);
        final ClientRequestContext derivedCtx = originalCtx.newDerivedContext(newRequest);
        assertThat(derivedCtx.endpoint()).isSameAs(originalCtx.endpoint());
        assertThat(derivedCtx.sessionProtocol()).isSameAs(originalCtx.sessionProtocol());
        assertThat(derivedCtx.scheme()).isSameAs(originalCtx.scheme());
        assertThat(derivedCtx.method()).isSameAs(originalCtx.method());
        assertThat(derivedCtx.options()).isSameAs(originalCtx.options());
        assertThat(derivedCtx.<Request>request()).isSameAs(newRequest);

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
        assertThat(derivedCtx.attr(foo).get()).isEqualTo("foo");

        // log is different
        assertThat(derivedCtx.log()).isNotSameAs(originalCtx.log());

        final AttributeKey<String> bar = AttributeKey.valueOf(DefaultClientRequestContextTest.class, "bar");
        originalCtx.attr(bar).set("bar");

        // the Attribute added to the original context after creation is not propagated to the derived context
        assertThat(derivedCtx.attr(bar).get()).isEqualTo(null);
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
