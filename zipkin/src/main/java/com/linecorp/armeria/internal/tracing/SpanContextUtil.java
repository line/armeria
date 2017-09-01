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
import io.netty.util.AttributeKey;

/**
 * Utility for pushing and popping a {@link Span} via a {@link RequestContext}.
 */
public final class SpanContextUtil {

    private static final AttributeKey<SpanInScope> SPAN_IN_SCOPE_KEY =
            AttributeKey.valueOf(SpanContextUtil.class, "SPAN_IN_SCOPE");

    /**
     * Sets up the {@link RequestContext} to push and pop the {@link Span} whenever it is entered/exited.
     */
    public static void setupContext(RequestContext ctx, Span span, Tracer tracer) {
        ctx.onEnter(unused -> ctx.attr(SPAN_IN_SCOPE_KEY).set(tracer.withSpanInScope(span)));
        ctx.onExit(unused -> {
            SpanInScope currentSpan = ctx.attr(SPAN_IN_SCOPE_KEY).getAndSet(null);
            if (currentSpan != null) {
                currentSpan.close();
            }
        });
    }

    private SpanContextUtil() {}
}
