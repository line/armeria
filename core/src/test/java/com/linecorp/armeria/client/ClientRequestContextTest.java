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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AttributeKey;

class ClientRequestContextTest {

    @Test
    void current() {
        assertThatThrownBy(ClientRequestContext::current).isInstanceOf(IllegalStateException.class)
                                                         .hasMessageContaining("unavailable");

        final ClientRequestContext ctx = clientRequestContext();
        assertThat(ctx.id()).isNotNull();
        try (SafeCloseable unused = ctx.push()) {
            assertThat(ClientRequestContext.current().unwrapAll()).isSameAs(ctx);
        }
        assertUnwrapAllCurrentCtx(null);

        try (SafeCloseable unused = serviceRequestContext().push()) {
            assertThatThrownBy(ClientRequestContext::current)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a client-side context");
        }
    }

    @Test
    void currentOrNull() {
        assertThat(ClientRequestContext.currentOrNull()).isNull();

        final ClientRequestContext ctx = clientRequestContext();
        try (SafeCloseable unused = ctx.push()) {
            assertThat(ClientRequestContext.currentOrNull().unwrapAll()).isSameAs(ctx);
        }
        assertUnwrapAllCurrentCtx(null);

        try (SafeCloseable unused = serviceRequestContext().push()) {
            assertThat(ClientRequestContext.currentOrNull()).isNull();
        }
    }

    @Test
    void mapCurrent() {
        assertThat(ClientRequestContext.mapCurrent(ctx -> "foo", () -> "bar")).isEqualTo("bar");
        assertThat(ClientRequestContext.mapCurrent(Function.identity(), null)).isNull();

        final ClientRequestContext ctx = clientRequestContext();
        try (SafeCloseable unused = ctx.push()) {
            assertThat(ClientRequestContext.mapCurrent(c -> "foo", () -> "bar")).isEqualTo("foo");
            assertThat(ClientRequestContext.mapCurrent(Function.identity(), null).unwrapAll())
                    .isSameAs(ctx);
        }
        assertUnwrapAllCurrentCtx(null);

        try (SafeCloseable unused = serviceRequestContext().push()) {
            assertThatThrownBy(() -> ClientRequestContext.mapCurrent(c -> "foo", () -> "bar"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a client-side context");
        }
    }

    @Test
    void pushReentrance() {
        final ClientRequestContext ctx = clientRequestContext();
        try (SafeCloseable ignored = ctx.push()) {
            assertUnwrapAllCurrentCtx(ctx);
            try (SafeCloseable ignored2 = ctx.push()) {
                assertUnwrapAllCurrentCtx(ctx);
            }
            assertUnwrapAllCurrentCtx(ctx);
        }
        assertUnwrapAllCurrentCtx(null);
    }

    @Test
    void pushWithOldServiceCtx() {
        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable ignored = sctx.push()) {
            assertUnwrapAllCurrentCtx(sctx);
            // The root of ClientRequestContext is sctx.
            final ClientRequestContext cctx = clientRequestContext();
            try (SafeCloseable ignored1 = cctx.push()) {
                assertUnwrapAllCurrentCtx(cctx);
                try (SafeCloseable ignored2 = sctx.push()) {
                    assertUnwrapAllCurrentCtx(sctx);
                }
                assertUnwrapAllCurrentCtx(cctx);
            }
            assertUnwrapAllCurrentCtx(sctx);
        }
        assertUnwrapAllCurrentCtx(null);
    }

    @Test
    void pushWithOldServiceCtx_exceptionWhenServiceCtxIsDifferFromRoot() {
        final ServiceRequestContext sctx1 = serviceRequestContext();
        final ClientRequestContext ctx;
        try (SafeCloseable ignored = sctx1.push()) {
            ctx = clientRequestContext();
        }
        final ServiceRequestContext sctx2 = serviceRequestContext();
        try (SafeCloseable ignored = sctx2.push()) {
            assertThatThrownBy(ctx::push).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void pushWithOldClientCtxWhoseRootIsSameServiceCtx_ctx2IsCreatedSameLayer() {
        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable ignored = sctx.push()) {
            assertUnwrapAllCurrentCtx(sctx);
            final ClientRequestContext cctx1 = clientRequestContext();
            final ClientRequestContext cctx2 = clientRequestContext();
            assertThat(cctx1.root()).isSameAs(cctx2.root());

            try (SafeCloseable ignored1 = cctx1.push()) {
                assertUnwrapAllCurrentCtx(cctx1);
                try (SafeCloseable ignored2 = cctx2.push()) {
                    assertUnwrapAllCurrentCtx(cctx2);
                }
                assertUnwrapAllCurrentCtx(cctx1);
            }
            assertUnwrapAllCurrentCtx(sctx);
        }
        assertUnwrapAllCurrentCtx(null);
    }

    @Test
    void pushWithOldClientCtxWhoseRootIsSameServiceCtx_ctx2IsCreatedUnderCtx1() {
        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable ignored = sctx.push()) {
            assertUnwrapAllCurrentCtx(sctx);
            final ClientRequestContext cctx1 = clientRequestContext();
            try (SafeCloseable ignored1 = cctx1.push()) {
                assertUnwrapAllCurrentCtx(cctx1);
                final ClientRequestContext cctx2 = clientRequestContext();
                assertThat(cctx1.root()).isSameAs(cctx2.root());

                try (SafeCloseable ignored2 = cctx2.push()) {
                    assertUnwrapAllCurrentCtx(cctx2);
                }
                assertUnwrapAllCurrentCtx(cctx1);
            }
            assertUnwrapAllCurrentCtx(sctx);
        }
        assertUnwrapAllCurrentCtx(null);
    }

    @Test
    void pushWithOldClientCtxWhoseRootIsSameServiceCtx_derivedCtx() {
        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable ignored = sctx.push()) {
            assertUnwrapAllCurrentCtx(sctx);
            final ClientRequestContext cctx1 = clientRequestContext();
            final ClientRequestContext derived = cctx1.newDerivedContext(cctx1.id(), cctx1.request(),
                                                                         cctx1.rpcRequest(), cctx1.endpoint());
            try (SafeCloseable ignored1 = derived.push()) {
                assertUnwrapAllCurrentCtx(derived);
                final ClientRequestContext cctx2 = clientRequestContext();
                assertThat(derived.root()).isSameAs(cctx2.root());

                try (SafeCloseable ignored2 = cctx2.push()) {
                    assertUnwrapAllCurrentCtx(cctx2);
                }
                assertUnwrapAllCurrentCtx(derived);
            }
            assertUnwrapAllCurrentCtx(sctx);
        }
        assertUnwrapAllCurrentCtx(null);
    }

    @Test
    void pushWithOldClientCtxWhoseRootIsDifferent() {
        final ServiceRequestContext sctx1 = serviceRequestContext();
        final ClientRequestContext cctx1;
        try (SafeCloseable ignored = sctx1.push()) {
            cctx1 = clientRequestContext();
        }
        final ServiceRequestContext sctx2 = serviceRequestContext();
        final ClientRequestContext cctx2;
        try (SafeCloseable ignored = sctx2.push()) {
            cctx2 = clientRequestContext();
        }
        try (SafeCloseable ignored = cctx1.push()) {
            assertThatThrownBy(cctx2::push).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void pushWithOldClientCtxWhoseRootIsNull() {
        final ClientRequestContext cctx1 = clientRequestContext();
        try (SafeCloseable ignored1 = cctx1.push()) {
            assertUnwrapAllCurrentCtx(cctx1);
            final ClientRequestContext cctx2 = clientRequestContext();
            assertThat(cctx1.root()).isNull();
            assertThat(cctx2.root()).isNull();
            try (SafeCloseable ignored2 = cctx2.push()) {
                assertUnwrapAllCurrentCtx(cctx2);
            }
            assertUnwrapAllCurrentCtx(cctx1);
        }
        assertUnwrapAllCurrentCtx(null);
    }

    @Test
    void hasAttr() {
        final AttributeKey<String> key = AttributeKey.valueOf(ClientRequestContextTest.class, "KEY");
        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable ignored = sctx.push()) {
            final ClientRequestContext cctx = clientRequestContext();
            assertThat(sctx.hasAttr(key)).isFalse();
            assertThat(cctx.hasAttr(key)).isFalse();

            sctx.setAttr(key, "foo");
            assertThat(sctx.hasAttr(key)).isTrue();
            assertThat(cctx.hasAttr(key)).isTrue();
        }
    }

    @Test
    void hasOwnAttr() {
        final AttributeKey<String> key = AttributeKey.valueOf(ClientRequestContextTest.class, "KEY");
        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable ignored = sctx.push()) {
            final ClientRequestContext cctx = clientRequestContext();
            assertThat(sctx.hasOwnAttr(key)).isFalse();
            assertThat(cctx.hasOwnAttr(key)).isFalse();

            sctx.setAttr(key, "foo");
            assertThat(sctx.hasOwnAttr(key)).isTrue();
            assertThat(cctx.hasOwnAttr(key)).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://host.com/path?a=b", "http://host.com/path?a=b",
                            "http://1.2.3.4:8080/path?a=b"})
    void updateRequestWithAbsolutePath(String path) {
        final ClientRequestContext clientRequestContext = clientRequestContext();
        assertThat(clientRequestContext.path()).isEqualTo("/");
        final HttpRequest request =
                HttpRequest.of(RequestHeaders.of(HttpMethod.GET, path));

        final URI uri = URI.create(path);

        clientRequestContext.updateRequest(request);

        // absolute path updates the authority, session protocol
        assertThat(clientRequestContext.authority()).isEqualTo(uri.getAuthority());
        assertThat(clientRequestContext.sessionProtocol().toString()).isEqualTo(uri.getScheme());
        assertThat(clientRequestContext.path()).isEqualTo("/path");
        assertThat(clientRequestContext.query()).isEqualTo("a=b");
        assertThat(clientRequestContext.uri().toString()).isEqualTo(path);
        assertThat(clientRequestContext.endpoint().authority()).isEqualTo(uri.getAuthority());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https:/path?a=b", "http:///"})
    void updateRequestWithInvalidPath(String path) {
        final ClientRequestContext clientRequestContext = clientRequestContext();
        assertThat(clientRequestContext.path()).isEqualTo("/");
        final HttpRequest request =
                HttpRequest.of(RequestHeaders.of(HttpMethod.GET, path));

        assertThatThrownBy(() -> clientRequestContext.updateRequest(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertUnwrapAllCurrentCtx(@Nullable RequestContext ctx) {
        final RequestContext current = RequestContext.currentOrNull();
        if (current == null) {
            assertThat(ctx).isNull();
        } else {
            assertThat(current.unwrapAll()).isSameAs(ctx);
        }
    }

    private static ServiceRequestContext serviceRequestContext() {
        return ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }

    private static ClientRequestContext clientRequestContext() {
        return ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }
}
