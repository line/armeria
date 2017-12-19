/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal.tracing;

import com.linecorp.armeria.common.RequestContext;

import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import io.netty.util.concurrent.FastThreadLocal;

/**
 * Utility for pushing and popping a {@link Span} via a {@link RequestContext}.
 */
public final class SpanContextUtil {

    private static final FastThreadLocal<SpanInScope> SPAN_IN_THREAD = new FastThreadLocal<>();

    /**
     * Sets up the {@link RequestContext} to push and pop the {@link Span} whenever it is entered/exited.
     */
    public static void setupContext(RequestContext ctx, Span span, Tracer tracer) {
        ctx.onEnter(unused -> SPAN_IN_THREAD.set(tracer.withSpanInScope(span)));
        ctx.onExit(unused -> {
            SpanInScope spanInScope = SPAN_IN_THREAD.get();
            if (spanInScope != null) {
                spanInScope.close();
                SPAN_IN_THREAD.remove();
            }
        });
    }

    private SpanContextUtil() {}
}
