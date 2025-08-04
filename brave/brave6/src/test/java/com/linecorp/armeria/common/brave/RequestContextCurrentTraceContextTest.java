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

package com.linecorp.armeria.common.brave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.brave.TraceContextUtil;
import com.linecorp.armeria.internal.common.brave.TraceContextUtil.PingPongExtra;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.TraceContext;
import io.netty.channel.EventLoop;

@MockitoSettings(strictness = Strictness.LENIENT)
class RequestContextCurrentTraceContextTest {

    RequestContext ctx;
    @Mock
    EventLoop eventLoop;

    private static final CurrentTraceContext currentTraceContext =
            RequestContextCurrentTraceContext.ofDefault();
    private static final TraceContext traceContext = TraceContext.newBuilder().traceId(1).spanId(1).build();

    @RegisterExtension
    static final EventLoopExtension eventLoopExtension = new EventLoopExtension();

    @BeforeEach
    void setUp() {
        when(eventLoop.inEventLoop()).thenReturn(true);
        when(eventLoop.next()).thenReturn(eventLoop);

        ctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                   .eventLoop(eventLoop)
                                   .build();
    }

    @Test
    public void get_returnsNullWhenNoCurrentRequestContext() {
        assertThat(currentTraceContext.get()).isNull();
    }

    @Test
    public void get_returnsNullWhenCurrentRequestContext_hasNoTraceAttribute() {
        try (SafeCloseable requestContextScope = ctx.push()) {
            assertThat(currentTraceContext.get()).isNull();
        }
    }

    @Test
    public void newScope_appliesWhenNoCurrentRequestContext() {
        try (Scope traceContextScope = currentTraceContext.newScope(traceContext)) {
            assertThat(traceContextScope).hasToString("ThreadLocalScope");
            assertThat(currentTraceContext.get()).isEqualTo(traceContext);
        }
    }

    @Test
    public void newScope_appliesWhenCurrentRequestContext() {
        try (SafeCloseable requestContextScope = ctx.push()) {
            try (Scope traceContextScope = currentTraceContext.newScope(traceContext)) {
                assertThat(currentTraceContext.get()).isEqualTo(traceContext);
            }
        }
    }

    @Test
    public void newScope_closeDoesntClearFirstScope() {
        final TraceContext traceContext2 = TraceContext.newBuilder().traceId(1).spanId(2).build();
        TraceContextUtil.setTraceContext(ctx, traceContext);

        try (SafeCloseable requestContextScope = ctx.push()) {
            try (Scope traceContextScope = currentTraceContext.newScope(traceContext)) {
                assertThat(currentTraceContext.get()).isEqualTo(traceContext);

                try (Scope traceContextScope2 = currentTraceContext.newScope(traceContext2)) {
                    assertThat(currentTraceContext.get()).isEqualTo(traceContext2);
                }
                assertThat(currentTraceContext.get()).isEqualTo(traceContext);
            }
            // the first scope is attached to the request context and cleared when that's destroyed
            assertThat(currentTraceContext.get()).isEqualTo(traceContext);
        }
    }

    @Test
    public void newScope_notOnEventLoop() {
        final TraceContext traceContext2 = TraceContext.newBuilder().traceId(1).spanId(2).build();
        TraceContextUtil.setTraceContext(ctx, traceContext);

        try (SafeCloseable requestContextScope = ctx.push()) {
            try (Scope traceContextScope = currentTraceContext.newScope(traceContext)) {
                assertThat(currentTraceContext.get()).isEqualTo(traceContext);

                when(eventLoop.inEventLoop()).thenReturn(false);
                try (Scope traceContextScope2 = currentTraceContext.newScope(traceContext2)) {
                    assertThat(traceContextScope2).hasToString("ThreadLocalScope");
                    assertThat(currentTraceContext.get()).isEqualTo(traceContext2);
                }
                when(eventLoop.inEventLoop()).thenReturn(true);
                assertThat(currentTraceContext.get()).isEqualTo(traceContext);
            }
            // the first scope is attached to the request context and cleared when that's destroyed
            assertThat(currentTraceContext.get()).isEqualTo(traceContext);
        }
    }

    @Test
    public void newScope_canClearScope() {
        try (SafeCloseable requestContextScope = ctx.push()) {
            try (Scope traceContextScope = currentTraceContext.newScope(traceContext)) {
                try (Scope traceContextScope2 = currentTraceContext.newScope(null)) {
                    assertThat(currentTraceContext.get()).isNull();
                }
                assertThat(currentTraceContext.get()).isEqualTo(traceContext);
            }
        }
    }

    @Test
    public void newScope_respondsToPing() {
        final PingPongExtra extra = new PingPongExtra();
        final TraceContext extraContext = TraceContext.newBuilder().traceId(1).spanId(1)
                                                      .addExtra(extra).build();

        try (Scope traceContextScope = currentTraceContext.newScope(extraContext)) {
            assertThat(traceContextScope).hasToString("NoopScope");
            assertThat(extra.isPong()).isTrue();
        }
    }

    @Test
    public void shouldSetPongIfOnlyExtra() {
        final PingPongExtra extra = new PingPongExtra();

        final TraceContext context = TraceContext.newBuilder().traceId(1).spanId(1)
                                                 .addExtra(extra).build();

        TraceContextUtil.PingPongExtra.maybeSetPong(context);

        assertThat(extra.isPong()).isTrue();
    }

    @Test
    void nonEventLoopPropagation() {
        final AtomicReference<TraceContext> ref = new AtomicReference<>();
        final RequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        try (Scope scope = currentTraceContext.newScope(traceContext)) {
            ctx.makeContextAware(eventLoopExtension.get()).submit(() -> {
                final TraceContext traceContext = currentTraceContext.get();
                ref.set(traceContext);
            });
        }
        await().untilAsserted(() -> assertThat(ref).doesNotHaveNullValue());
        assertThat(ref.get()).isSameAs(traceContext);
    }

    @Test
    void nonEventLoopFallback() {
        final AtomicReference<TraceContext> ref = new AtomicReference<>();
        final RequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        // fallback to the last context set to the RequestContext manually
        TraceContextUtil.setTraceContext(ctx, traceContext);

        ctx.makeContextAware(eventLoopExtension.get()).submit(() -> {
            final TraceContext traceContext = currentTraceContext.get();
            ref.set(traceContext);
        });

        await().untilAsserted(() -> assertThat(ref).doesNotHaveNullValue());
        assertThat(ref.get()).isSameAs(traceContext);
    }
}
