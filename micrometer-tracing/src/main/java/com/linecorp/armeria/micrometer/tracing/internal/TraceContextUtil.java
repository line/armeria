/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.micrometer.tracing.internal;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.micrometer.tracing.common.ArmeriaCurrentTraceContext;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.netty.util.AttributeKey;

public final class TraceContextUtil {

    private static final AttributeKey<TraceContext> TRACE_CONTEXT_KEY =
            AttributeKey.valueOf(TraceContextUtil.class, "TRACE_CONTEXT");

    @Nullable
    public static TraceContext traceContext(RequestContext ctx) {
        return ctx.attr(TRACE_CONTEXT_KEY);
    }

    public static void setTraceContext(RequestContext ctx, TraceContext traceContext) {
        ctx.setAttr(TRACE_CONTEXT_KEY, traceContext);
    }

    /**
     * Ensures the specified {@link Tracer} uses a {@link ArmeriaCurrentTraceContext}.
     *
     * @throws IllegalStateException if {@code tracing} does not use {@link ArmeriaCurrentTraceContext}
     */
    public static void ensureScopeUsesRequestContext(Tracer tracer) {
        requireNonNull(tracer, "tracer");
        if (!(tracer.currentTraceContext() instanceof ArmeriaCurrentTraceContext)) {
            throw new IllegalStateException(
                    "Tracer.currentTraceContext is not a " + ArmeriaCurrentTraceContext.class
                            .getSimpleName() + " scope. " +
                    "Consider using " + ArmeriaCurrentTraceContext.class
                            .getSimpleName() + " for better interoperability.");
        }
    }

    private TraceContextUtil() {}
}
