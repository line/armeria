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

package com.linecorp.armeria.micrometer.tracing.otel;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;

/**
 * A {@link ContextStorage} implementation based on Armeria's {@link RequestContext}.
 */
public final class ArmeriaOtelContextStorage implements ContextStorage {

    private static final Scope INITIAL_REQUEST_SCOPE = () -> {};

    /**
     * Returns the {@link ArmeriaOtelContextStorage} singleton.
     */
    public static ArmeriaOtelContextStorage of() {
        return new ArmeriaOtelContextStorage();
    }

    private static final AttributeKey<Context> TRACE_CONTEXT_KEY =
            AttributeKey.valueOf(ArmeriaOtelContextStorage.class, "TRACE_CONTEXT");

    private ArmeriaOtelContextStorage() {}

    @Nullable
    public static Context traceContext(RequestContext ctx) {
        return ctx.attr(TRACE_CONTEXT_KEY);
    }

    public static void setTraceContext(RequestContext ctx, Context traceContext) {
        ctx.setAttr(TRACE_CONTEXT_KEY, traceContext);
    }

    @Override
    public Scope attach(Context toAttach) {
        final RequestContext ctx = RequestContext.current();
        final Context previous = traceContext(ctx);
        setTraceContext(ctx, toAttach);

        // Don't remove the outer-most context (client or server request)
        if (previous == null) {
            return INITIAL_REQUEST_SCOPE;
        }

        return () -> {
            final RequestContext ctx0 = RequestContext.currentOrNull();
            if (ctx0 != null) {
                setTraceContext(ctx0, previous);
            }
        };
    }

    @Override
    public Context current() {
        return traceContext(RequestContext.current());
    }
}
