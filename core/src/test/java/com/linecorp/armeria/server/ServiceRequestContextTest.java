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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Deque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

class ServiceRequestContextTest {

    private final Deque<RequestContext> onEnterExitStack = new LinkedBlockingDeque<>();

    @BeforeEach
    void clear() {
        onEnterExitStack.clear();
    }

    @Test
    void current() {
        assertThatThrownBy(ServiceRequestContext::current).isInstanceOf(IllegalStateException.class)
                                                          .hasMessageContaining("unavailable");

        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable unused = sctx.push()) {
            assertThat(onEnterExitStack).hasSize(1);
            assertThat(ServiceRequestContext.current()).isSameAs(sctx);
            final ClientRequestContext cctx = clientRequestContext();
            try (SafeCloseable unused1 = cctx.push()) {
                assertThat(onEnterExitStack).hasSize(2);
                assertThat(ServiceRequestContext.current()).isSameAs(sctx);
                assertThat(ClientRequestContext.current()).isSameAs(cctx);
                assertThat((ClientRequestContext) RequestContext.current()).isSameAs(cctx);
            }
            assertThat(onEnterExitStack).hasSize(1);
        }
        assertThat(onEnterExitStack).isEmpty();

        try (SafeCloseable unused = clientRequestContext().push()) {
            assertThatThrownBy(ServiceRequestContext::current)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a server-side context");
        }
    }

    @Test
    void currentOrNull() {
        assertThat(ServiceRequestContext.currentOrNull()).isNull();

        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable unused = sctx.push()) {
            assertThat(onEnterExitStack).hasSize(1);
            assertThat(ServiceRequestContext.currentOrNull()).isSameAs(sctx);
            final ClientRequestContext cctx = clientRequestContext();
            try (SafeCloseable unused1 = cctx.push()) {
                assertThat(onEnterExitStack).hasSize(2);
                assertThat(ServiceRequestContext.currentOrNull()).isSameAs(sctx);
                assertThat(ClientRequestContext.current()).isSameAs(cctx);
                assertThat((ClientRequestContext) RequestContext.current()).isSameAs(cctx);
            }
            assertThat(onEnterExitStack).hasSize(1);
        }
        assertThat(onEnterExitStack).isEmpty();

        try (SafeCloseable unused = clientRequestContext().push()) {
            assertThatThrownBy(ServiceRequestContext::currentOrNull)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a server-side context");
        }
    }

    @Test
    void mapCurrent() {
        assertThat(ServiceRequestContext.mapCurrent(ctx -> "foo", () -> "defaultValue"))
                .isEqualTo("defaultValue");
        assertThat(ServiceRequestContext.mapCurrent(Function.identity(), null)).isNull();

        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable unused = sctx.push()) {
            assertThat(onEnterExitStack).hasSize(1);
            assertThat(ServiceRequestContext.mapCurrent(c -> c == sctx ? "foo" : "bar",
                                                        () -> "defaultValue"))
                    .isEqualTo("foo");
            assertThat(ServiceRequestContext.mapCurrent(Function.identity(), null)).isSameAs(sctx);
            final ClientRequestContext cctx = clientRequestContext();
            try (SafeCloseable unused1 = cctx.push()) {
                assertThat(onEnterExitStack).hasSize(2);
                assertThat(ServiceRequestContext.mapCurrent(c -> c == sctx ? "foo" : "bar",
                                                            () -> "defaultValue"))
                        .isEqualTo("foo");
                assertThat(ClientRequestContext.mapCurrent(c -> c == cctx ? "baz" : "qux",
                                                           () -> "defaultValue"))
                        .isEqualTo("baz");
                assertThat(ServiceRequestContext.mapCurrent(Function.identity(), null)).isSameAs(sctx);
                assertThat(ClientRequestContext.mapCurrent(Function.identity(), null)).isSameAs(cctx);
                assertThat(RequestContext.mapCurrent(Function.identity(), null)).isSameAs(cctx);
            }
            assertThat(onEnterExitStack).hasSize(1);
        }
        assertThat(onEnterExitStack).isEmpty();

        try (SafeCloseable unused = clientRequestContext().push()) {
            assertThatThrownBy(() -> ServiceRequestContext.mapCurrent(c -> "foo", () -> "bar"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a server-side context");
        }
    }

    @Test
    void pushReentrance() {
        final ServiceRequestContext ctx = serviceRequestContext();
        try (SafeCloseable ignored = ctx.push()) {
            assertCurrentCtx(ctx);
            assertThat(onEnterExitStack).hasSize(1);
            try (SafeCloseable ignored2 = ctx.push()) {
                assertCurrentCtx(ctx);
                assertThat(onEnterExitStack).hasSize(1);
            }
            assertCurrentCtx(ctx);
            assertThat(onEnterExitStack).hasSize(1);
        }
        assertThat(onEnterExitStack).isEmpty();
    }

    @Test
    void pushWithOldClientCtxWhoseRootIsThisServiceCtx() {
        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable ignored = sctx.push()) {
            assertCurrentCtx(sctx);
            assertThat(onEnterExitStack).hasSize(1);
            // The root of ClientRequestContext is sctx.
            final ClientRequestContext cctx = clientRequestContext();
            try (SafeCloseable ignored1 = cctx.push()) {
                assertCurrentCtx(cctx);
                assertThat(onEnterExitStack).hasSize(2);
                try (SafeCloseable ignored2 = sctx.push()) {
                    assertCurrentCtx(sctx);
                    assertThat(onEnterExitStack).hasSize(2);
                }
                assertCurrentCtx(cctx);
                assertThat(onEnterExitStack).hasSize(2);
            }
            assertCurrentCtx(sctx);
            assertThat(onEnterExitStack).hasSize(1);
        }
        assertThat(onEnterExitStack).isEmpty();
    }

    @Test
    void pushWithOldIrrelevantClientCtx() {
        final ClientRequestContext cctx = clientRequestContext();
        try (SafeCloseable ignored = cctx.push()) {
            assertCurrentCtx(cctx);
            assertThat(onEnterExitStack).hasSize(1);
            final ServiceRequestContext sctx = serviceRequestContext();
            assertThatThrownBy(sctx::push).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void pushWithOldIrrelevantServiceCtx() {
        final ServiceRequestContext sctx1 = serviceRequestContext();
        final ServiceRequestContext sctx2 = serviceRequestContext();
        try (SafeCloseable ignored = sctx1.push()) {
            assertCurrentCtx(sctx1);
            assertThat(onEnterExitStack).hasSize(1);
            assertThatThrownBy(sctx2::push).isInstanceOf(IllegalStateException.class);
        }
    }

    private static void assertCurrentCtx(RequestContext ctx) {
        final RequestContext current = RequestContext.current();
        assertThat(current).isSameAs(ctx);
    }

    private ServiceRequestContext serviceRequestContext() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        addHooks(ctx);
        return ctx;
    }

    private ClientRequestContext clientRequestContext() {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        addHooks(ctx);
        return ctx;
    }

    private void addHooks(RequestContext ctx) {
        ctx.onEnter(onEnterExitStack::addLast);
        ctx.onExit(onExitCtx -> onEnterExitStack.removeLast());
    }
}
