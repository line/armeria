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

import java.util.Deque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

class ClientRequestContextTest {

    private final Deque<RequestContext> onEnterExitStack = new LinkedBlockingDeque<>();

    private final Deque<OnChildEntry> onChildCallbacks = new LinkedBlockingDeque<>();

    @BeforeEach
    void clear() {
        onEnterExitStack.clear();
        onChildCallbacks.clear();
    }

    @Test
    void current() {
        assertThatThrownBy(ClientRequestContext::current).isInstanceOf(IllegalStateException.class)
                                                         .hasMessageContaining("unavailable");

        final ClientRequestContext ctx = clientRequestContext();
        assertThat(ctx.id()).isNotNull();
        try (SafeCloseable unused = ctx.push()) {
            assertThat(onEnterExitStack.size()).isOne();
            assertThat(ClientRequestContext.current()).isSameAs(ctx);
        }
        assertThat(onEnterExitStack.size()).isZero();

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
            assertThat(onEnterExitStack.size()).isOne();
            assertThat(ClientRequestContext.currentOrNull()).isSameAs(ctx);
        }
        assertThat(onEnterExitStack.size()).isZero();

        try (SafeCloseable unused = serviceRequestContext().push()) {
            assertThatThrownBy(ClientRequestContext::currentOrNull)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a client-side context");
        }
    }

    @Test
    void mapCurrent() {
        assertThat(ClientRequestContext.mapCurrent(ctx -> "foo", () -> "bar")).isEqualTo("bar");
        assertThat(ClientRequestContext.mapCurrent(Function.identity(), null)).isNull();

        final ClientRequestContext ctx = clientRequestContext();
        try (SafeCloseable unused = ctx.push()) {
            assertThat(onEnterExitStack.size()).isOne();
            assertThat(ClientRequestContext.mapCurrent(c -> "foo", () -> "bar")).isEqualTo("foo");
            assertThat(ClientRequestContext.mapCurrent(Function.identity(), null)).isSameAs(ctx);
        }
        assertThat(onEnterExitStack.size()).isZero();

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
            assertThat(onEnterExitStack.size()).isOne();
            try (SafeCloseable ignored2 = ctx.push()) {
                assertThat(onEnterExitStack.size()).isOne();
            }
            assertThat(onEnterExitStack.size()).isOne();
        }
        assertThat(onEnterExitStack.size()).isZero();
    }

    @Test
    void pushWithOldServiceCtx() {
        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable ignored = sctx.push()) {
            assertThat(onEnterExitStack.size()).isOne();
            // The root of ClientRequestContext is sctx.
            try (SafeCloseable ignored1 = clientRequestContext().push()) {
                assertThat(onEnterExitStack.size()).isEqualTo(2);
                try (SafeCloseable ignored2 = sctx.push()) {
                    assertThat(onEnterExitStack.size()).isEqualTo(2);
                }
                assertThat(onEnterExitStack.size()).isEqualTo(2);
            }
            assertThat(onEnterExitStack.size()).isOne();
        }
        assertThat(onEnterExitStack.size()).isZero();
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
            assertThat(onEnterExitStack.size()).isOne();
            final ClientRequestContext cctx1 = clientRequestContext();
            final ClientRequestContext cctx2 = clientRequestContext();
            assertThat(cctx1.root()).isSameAs(cctx2.root());

            try (SafeCloseable ignored1 = cctx1.push()) {
                assertThat(onEnterExitStack.size()).isEqualTo(2);
                OnChildEntry onChild = onChildCallbacks.getLast();
                assertThat(onChild.curCtx).isSameAs(sctx);
                assertThat(onChild.newCtx).isSameAs(cctx1);

                try (SafeCloseable ignored2 = cctx2.push()) {
                    assertThat(onEnterExitStack.size()).isEqualTo(3);
                    onChild = onChildCallbacks.getLast();
                    assertThat(onChild.curCtx).isSameAs(sctx);
                    assertThat(onChild.newCtx).isSameAs(cctx2);
                }
                assertThat(onEnterExitStack.size()).isEqualTo(2);
            }
            assertThat(onEnterExitStack.size()).isOne();
        }
        assertThat(onEnterExitStack.size()).isZero();
    }

    @Test
    void pushWithOldClientCtxWhoseRootIsSameServiceCtx__ctx2IsCreatedUnderCtx1() {
        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable ignored = sctx.push()) {
            assertThat(onEnterExitStack.size()).isOne();
            final ClientRequestContext cctx1 = clientRequestContext();
            try (SafeCloseable ignored1 = cctx1.push()) {
                final ClientRequestContext cctx2 = clientRequestContext();
                assertThat(cctx1.root()).isSameAs(cctx2.root());
                assertThat(onEnterExitStack.size()).isEqualTo(2);
                OnChildEntry onChild = onChildCallbacks.getLast();
                assertThat(onChild.curCtx).isSameAs(sctx);
                assertThat(onChild.newCtx).isSameAs(cctx1);

                try (SafeCloseable ignored2 = cctx2.push()) {
                    assertThat(onEnterExitStack.size()).isEqualTo(3);
                    onChild = onChildCallbacks.getLast();
                    assertThat(onChild.curCtx).isSameAs(sctx);
                    assertThat(onChild.newCtx).isSameAs(cctx2);
                }
                assertThat(onEnterExitStack.size()).isEqualTo(2);
            }
            assertThat(onEnterExitStack.size()).isOne();
        }
        assertThat(onEnterExitStack.size()).isZero();
    }

    @Test
    void pushWithOldClientCtxWhoseRootIsSameServiceCtx_derivedCtx() {
        final ServiceRequestContext sctx = serviceRequestContext();
        try (SafeCloseable ignored = sctx.push()) {
            assertThat(onEnterExitStack.size()).isOne();
            final ClientRequestContext cctx1 = clientRequestContext();
            final ClientRequestContext derived = derivedClientRequestContext(cctx1);
            try (SafeCloseable ignored1 = derived.push()) {
                final ClientRequestContext cctx2 = clientRequestContext();
                assertThat(derived.root()).isSameAs(cctx2.root());
                assertThat(onEnterExitStack.size()).isEqualTo(2);
                OnChildEntry onChild = onChildCallbacks.getLast();
                assertThat(onChild.curCtx).isSameAs(sctx);
                assertThat(onChild.newCtx).isSameAs(derived);

                try (SafeCloseable ignored2 = cctx2.push()) {
                    assertThat(onEnterExitStack.size()).isEqualTo(3);
                    onChild = onChildCallbacks.getLast();
                    assertThat(onChild.curCtx).isSameAs(sctx);
                    assertThat(onChild.newCtx).isSameAs(cctx2);
                }
                assertThat(onEnterExitStack.size()).isEqualTo(2);
            }
            assertThat(onEnterExitStack.size()).isOne();
        }
        assertThat(onEnterExitStack.size()).isZero();
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
            final ClientRequestContext cctx2 = clientRequestContext();
            assertThat(cctx1.root()).isNull();
            assertThat(cctx2.root()).isNull();
            assertThat(onChildCallbacks.size()).isZero();
            assertThat(onEnterExitStack.size()).isOne();
            try (SafeCloseable ignored2 = cctx2.push()) {
                assertThat(onChildCallbacks.size()).isZero();
                assertThat(onEnterExitStack.size()).isEqualTo(2);
            }
            assertThat(onEnterExitStack.size()).isOne();
        }
        assertThat(onEnterExitStack.size()).isZero();
    }

    private ServiceRequestContext serviceRequestContext() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.onChild((curCtx, newCtx) -> onChildCallbacks.add(new OnChildEntry(curCtx, newCtx)));
        addHooks(ctx);
        return ctx;
    }

    private ClientRequestContext clientRequestContext() {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        addHooks(ctx);
        return ctx;
    }

    private ClientRequestContext derivedClientRequestContext(ClientRequestContext ctx) {
        final ClientRequestContext derived = ctx.newDerivedContext(ctx.id(), ctx.request(), ctx.rpcRequest());
        addHooks(derived);
        return derived;
    }

    private void addHooks(RequestContext ctx) {
        ctx.onEnter(onEnterExitStack::addLast);
        ctx.onExit(onExitCtx -> onEnterExitStack.removeLast());
    }

    private static final class OnChildEntry {
        final ServiceRequestContext curCtx;
        final ClientRequestContext newCtx;

        private OnChildEntry(ServiceRequestContext curCtx, ClientRequestContext newCtx) {
            this.curCtx = curCtx;
            this.newCtx = newCtx;
        }
    }
}
