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

import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.internal.tracing.PingPongExtra;

import brave.propagation.CurrentTraceContext;
import brave.propagation.TraceContext;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/**
 * {@linkplain brave.Tracing.Builder#currentTraceContext(brave.propagation.CurrentTraceContext) Tracing
 * context} implemented with {@linkplain com.linecorp.armeria.common.RequestContext}.
 *
 * <p>This {@link CurrentTraceContext} stores/loads the trace context into/from a
 * {@link RequestContext}'s attribute so that there's no need for thread local variables
 * which can lead to unpredictable behavior in asynchronous programming.
 */
public final class RequestContextCurrentTraceContext extends CurrentTraceContext {

    public static final CurrentTraceContext INSTANCE = new RequestContextCurrentTraceContext();
    private static final Logger logger = LoggerFactory.getLogger(RequestContextCurrentTraceContext.class);
    private static final AttributeKey<TraceContext> TRACE_CONTEXT_KEY =
            AttributeKey.newInstance("trace-context");

    private static final Scope INCOMPLETE_CONFIGURATION_SCOPE = new Scope() {
        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return "IncompleteConfigurationScope";
        }
    };

    private static final Scope INITIAL_REQUEST_SCOPE = new Scope() {
        @Override
        public void close() {
            // Don't remove the outer-most context (client or server request)
        }

        @Override
        public String toString() {
            return "InitialRequestScope";
        }
    };

    /**
     * Use this to ensure the trace context propagates to children.
     *
     * <p>Ex.
     * <pre>{@code
     *  // Ensure the trace context propagates to children
     * ctx.onChild(RequestContextCurrentTraceContext::copy);
     * }</pre>
     */
    public static void copy(RequestContext src, RequestContext dst) {
        dst.attr(TRACE_CONTEXT_KEY).set(src.attr(TRACE_CONTEXT_KEY).get());
    }

    @Override
    public TraceContext get() {
        if (!ensureCurrentRequestContextOrWarnOnce()) {
            return null;
        }
        return RequestContext.current().attr(TRACE_CONTEXT_KEY).get();
    }

    @Override
    public Scope newScope(@Nullable TraceContext currentSpan) {
        // Handle inspection added to ensure we can fail-fast if this isn't installed.
        if (currentSpan != null && PingPongExtra.maybeSetPong(currentSpan)) {
            return INCOMPLETE_CONFIGURATION_SCOPE;
        }

        if (!ensureCurrentRequestContextOrWarnOnce()) {
            return INCOMPLETE_CONFIGURATION_SCOPE;
        }

        final Attribute<TraceContext> contextAttribute = RequestContext.current().attr(TRACE_CONTEXT_KEY);
        final TraceContext previous = contextAttribute.get();
        contextAttribute.set(currentSpan);

        // Don't remove the outer-most context (client or server request)
        if (previous == null) { return INITIAL_REQUEST_SCOPE; }

        // Removes sub-spans (i.e. local spans) from the current context when Brave's scope does.
        // If an asynchronous sub-span, it may still complete later.
        class RequestContextTraceContextScope implements Scope {
            @Override
            public void close() {
                contextAttribute.set(previous);
            }
        }
        return new RequestContextTraceContextScope();
    }

    /** Armeria code should always have a request context available, and this won't work without it. */
    private static boolean ensureCurrentRequestContextOrWarnOnce() {
        return RequestContext.mapCurrent(Function.identity(), LogRequestContextWarningOnce.INSTANCE) != null;
    }

    private RequestContextCurrentTraceContext() {
    }

    private enum LogRequestContextWarningOnce implements Supplier<RequestContext> {

        INSTANCE;

        @Override
        public RequestContext get() {
            ClassLoaderHack.loadMe();
            return null;
        }

        /**
         * This won't be referenced until {@link #get()} is called. If there's only one classloader, the initializer
         * will only be called once.
         */
        static class ClassLoaderHack {
            static void loadMe() {
            }

            static {
                logger.warn("Attempted to propagate trace context, but no request context available. " +
                            "Did you remember to use RequestContext.contextAwareExecutor() or RequestContext.makeContextAware()");
            }
        }
    }
}
