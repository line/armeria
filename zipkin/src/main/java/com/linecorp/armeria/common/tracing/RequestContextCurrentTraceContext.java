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

import java.util.function.Consumer;
import java.util.function.Function;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.internal.tracing.LogRequestContextWarningOnce;
import com.linecorp.armeria.internal.tracing.PingPongExtra;

import brave.propagation.CurrentTraceContext;
import brave.propagation.TraceContext;
import io.netty.util.AttributeKey;

/**
 * {@link brave.Tracing.Builder#currentTraceContext(brave.propagation.CurrentTraceContext) Tracing
 * context} implemented with {@link com.linecorp.armeria.common.RequestContext}.
 *
 * <p>This is an alternative to synchronizing brave's try/finally style with request scope lifecycle hooks such
 * as {@link RequestContext#onEnter(Consumer)} and {@link RequestContext#onExit(Consumer)}. Instead, this defers
 * control to Armeria, which manages the attribute {@link #TRACE_CONTEXT_KEY}.
 */
public final class RequestContextCurrentTraceContext extends CurrentTraceContext {

    public static final AttributeKey<TraceContext> TRACE_CONTEXT_KEY =
            AttributeKey.newInstance("trace-context");

    @Override
    public TraceContext get() {
        if (hasCurrentRequestContextOrWarnOnce()) {
            return null;
        }
        return RequestContext.current().attr(TRACE_CONTEXT_KEY).get();
    }

    @Override
    public CurrentTraceContext.Scope newScope(TraceContext currentSpan) {
        // Handle inspection added to ensure we can fail-fast if this isn't installed.
        if (PingPongExtra.maybeSetPong(currentSpan)) { return Scope.NOOP; }

        if (hasCurrentRequestContextOrWarnOnce()) {
            return Scope.NOOP;
        }

        final TraceContext previous = RequestContext.current().attr(TRACE_CONTEXT_KEY).get();
        RequestContext.current().attr(TRACE_CONTEXT_KEY).set(currentSpan);

        // Don't remove the outer-most context (client or server request)
        if (previous == null) { return Scope.NOOP; }

        // Allow sub-spans (aka local spans) to close when Brave's scope does.
        class RequestContextTraceContextScope implements CurrentTraceContext.Scope {
            @Override
            public void close() {
                RequestContext.current().attr(TRACE_CONTEXT_KEY).set(previous);
            }
        }
        return new RequestContextTraceContextScope();
    }

    /** Armeria code should always have a request context available, and this won't work without it. */
    static boolean hasCurrentRequestContextOrWarnOnce() {
        return RequestContext.mapCurrent(Function.identity(), LogRequestContextWarningOnce.INSTANCE)
               == null;
    }
}
