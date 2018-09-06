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

package com.linecorp.armeria.common.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.tracing.RequestContextCurrentTraceContext.PingPongExtra;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.DefaultAttributeMap;

import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.TraceContext;
import io.netty.channel.EventLoop;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

public class RequestContextCurrentTraceContextTest {
    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock(answer = Answers.CALLS_REAL_METHODS)
    RequestContext mockRequestContext;
    @Mock(answer = Answers.CALLS_REAL_METHODS)
    RequestContext mockRequestContext2;
    @Mock
    EventLoop eventLoop;

    final CurrentTraceContext currentTraceContext = RequestContextCurrentTraceContext.DEFAULT;
    final DefaultAttributeMap attrs1 = new DefaultAttributeMap();
    final DefaultAttributeMap attrs2 = new DefaultAttributeMap();
    final TraceContext traceContext = TraceContext.newBuilder().traceId(1).spanId(1).build();

    @Before
    public void setup() {
        when(mockRequestContext.eventLoop()).thenReturn(eventLoop);
        when(mockRequestContext2.eventLoop()).thenReturn(eventLoop);
        when(eventLoop.inEventLoop()).thenReturn(true);

        when(mockRequestContext.attr(isA(AttributeKey.class))).thenAnswer(
                (Answer<Attribute>) invocation -> attrs1.attr(invocation.getArgument(0)));
        when(mockRequestContext2.attr(isA(AttributeKey.class))).thenAnswer(
                (Answer<Attribute>) invocation -> attrs2.attr(invocation.getArgument(0)));
    }

    @Test
    public void copy() {
        try (SafeCloseable requestContextScope = mockRequestContext.push()) {
            try (Scope traceContextScope = currentTraceContext.newScope(traceContext)) {
                RequestContextCurrentTraceContext.copy(mockRequestContext, mockRequestContext2);
                assertThat(attrs1.attrs().next().get())
                        .isEqualTo(traceContext)
                        .isEqualTo(attrs2.attrs().next().get());
            }
        }
    }

    @Test
    public void get_returnsNullWhenNoCurrentRequestContext() {
        assertThat(currentTraceContext.get()).isNull();
    }

    @Test
    public void get_returnsNullWhenCurrentRequestContext_hasNoTraceAttribute() {
        try (SafeCloseable requestContextScope = mockRequestContext.push()) {
            assertThat(currentTraceContext.get()).isNull();
        }
    }

    @Test
    public void newScope_doesNothingWhenNoCurrentRequestContext() {
        try (Scope traceContextScope = currentTraceContext.newScope(traceContext)) {
            assertThat(traceContextScope).hasToString("IncompleteConfigurationScope");
            assertThat(currentTraceContext.get()).isNull();
        }
    }

    @Test
    public void newScope_appliesWhenCurrentRequestContext() {
        try (SafeCloseable requestContextScope = mockRequestContext.push()) {
            try (Scope traceContextScope = currentTraceContext.newScope(traceContext)) {
                assertThat(traceContextScope).hasToString("InitialRequestScope");
                assertThat(currentTraceContext.get()).isEqualTo(traceContext);
            }
        }
    }

    @Test
    public void newScope_closeDoesntClearFirstScope() {
        final TraceContext traceContext2 = TraceContext.newBuilder().traceId(1).spanId(2).build();

        try (SafeCloseable requestContextScope = mockRequestContext.push()) {
            try (Scope traceContextScope = currentTraceContext.newScope(traceContext)) {
                assertThat(traceContextScope).hasToString("InitialRequestScope");
                assertThat(currentTraceContext.get()).isEqualTo(traceContext);

                try (Scope traceContextScope2 = currentTraceContext.newScope(traceContext2)) {
                    assertThat(traceContextScope2).hasToString("RequestContextTraceContextScope");
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

        try (SafeCloseable requestContextScope = mockRequestContext.push()) {
            try (Scope traceContextScope = currentTraceContext.newScope(traceContext)) {
                assertThat(traceContextScope).hasToString("InitialRequestScope");
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
        try (SafeCloseable requestContextScope = mockRequestContext.push()) {
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
                                                      .extra(Collections.singletonList(extra)).build();

        try (Scope traceContextScope = currentTraceContext.newScope(extraContext)) {
            assertThat(traceContextScope).hasToString("NoopScope");
            assertThat(extra.isPong()).isTrue();
        }
    }

    @Test
    public void shouldSetPongIfOnlyExtra() {
        final PingPongExtra extra = new PingPongExtra();

        final TraceContext context = TraceContext.newBuilder().traceId(1).spanId(1)
                                                 .extra(Collections.singletonList(extra)).build();

        PingPongExtra.maybeSetPong(context);

        assertThat(extra.isPong()).isTrue();
    }
}
