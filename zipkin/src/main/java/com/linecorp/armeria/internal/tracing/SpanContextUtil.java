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

import java.util.Collections;

import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.tracing.RequestContextCurrentTraceContext;

import brave.Span;
import brave.Tracing;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.TraceContext;

public final class SpanContextUtil {

    /**
     * Trace context scopes are often wrapped, for example to decorate MDC. This creates a dummy scope to
     * ensure {@link RequestContextCurrentTraceContext#INSTANCE} is configured, even if it is wrapped.
     */
    public static void ensureScopeUsesRequestContext(Tracing tracing) {
        final PingPongExtra extra = new PingPongExtra();
        // trace contexts are not recorded until Tracer.toSpan, so this won't end up as junk data
        final TraceContext dummyContext = TraceContext.newBuilder().traceId(1).spanId(1)
                                                      .extra(Collections.singletonList(extra)).build();
        final boolean scopeUsesRequestContext;
        try (Scope scope = tracing.currentTraceContext().newScope(dummyContext)) {
            scopeUsesRequestContext = extra.isPong();
        }
        if (!scopeUsesRequestContext) {
            throw new IllegalStateException(
                    "Tracing.currentTraceContext is not a " + RequestContextCurrentTraceContext.class
                            .getSimpleName() + " scope. " +
                    "Please call Tracing.Builder.currentTraceContext(" + RequestContextCurrentTraceContext.class
                            .getSimpleName() + ".INSTANCE).");
        }
    }

    /**
     * Adds logging tags to the provided {@link Span} and closes it.
     * The span cannot be used further after this method has been called.
     */
    public static void closeSpan(Span span, RequestLog log) {
        SpanTags.addTags(span, log);
        span.finish();
    }

    private SpanContextUtil() {}
}
