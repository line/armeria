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

import java.util.Collections;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.tracing.HttpTracingClient;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.tracing.HttpTracingService;

import brave.Tracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.TraceContext;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/**
 * {@linkplain brave.Tracing.Builder#currentTraceContext(brave.propagation.CurrentTraceContext) Tracing
 * context} implemented with {@link com.linecorp.armeria.common.RequestContext}.
 *
 * <p>This {@link CurrentTraceContext} stores/loads the trace context into/from a
 * {@link RequestContext}'s attribute so that there's no need for thread local variables
 * which can lead to unpredictable behavior in asynchronous programming.
 */
public final class RequestContextCurrentTraceContext extends CurrentTraceContext {

    /**
     * Use this singleton when building a {@link brave.Tracing} instance for use with
     * {@link HttpTracingService} or {@link HttpTracingClient}.
     *
     * <p>If you need to customize the context, use {@link #newBuilder()} instead.
     *
     * @see brave.Tracing.Builder#currentTraceContext(brave.propagation.CurrentTraceContext)
     */
    public static final CurrentTraceContext DEFAULT = new RequestContextCurrentTraceContext(new Builder());

    /**
     * Singleton retained for backwards compatibility, but replaced by {@link #DEFAULT}.
     *
     * @deprecated Please use {@link #DEFAULT} or {@link #newBuilder()} to customize an instance.
     */
    @Deprecated
    public static final CurrentTraceContext INSTANCE = DEFAULT;

    private static final Logger logger = LoggerFactory.getLogger(RequestContextCurrentTraceContext.class);
    private static final AttributeKey<TraceContext> TRACE_CONTEXT_KEY =
            AttributeKey.valueOf(RequestContextCurrentTraceContext.class, "TRACE_CONTEXT");

    // Thread-local for storing TraceContext when invoking callbacks off the request thread.
    private static final ThreadLocal<TraceContext> THREAD_LOCAL_CONTEXT = new ThreadLocal<>();

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

    static final class Builder extends CurrentTraceContext.Builder {

        @Override
        public CurrentTraceContext build() {
            return new RequestContextCurrentTraceContext(this);
        }

        Builder() {
        }
    }

    /**
     * Use this when you need customizations such as log integration via
     * {@linkplain Builder#addScopeDecorator(ScopeDecorator)}.
     *
     * @see brave.Tracing.Builder#currentTraceContext(brave.propagation.CurrentTraceContext)
     */
    public static CurrentTraceContext.Builder newBuilder() {
        return new Builder();
    }

    /**
     * Ensures the specified {@link Tracing} uses a {@link RequestContextCurrentTraceContext}.
     *
     * @throws IllegalStateException if {@code tracing} does not use {@link RequestContextCurrentTraceContext}
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

    private RequestContextCurrentTraceContext(Builder builder) {
        super(builder);
    }

    @Override
    @Nullable
    public TraceContext get() {
        final RequestContext ctx = getRequestContextOrWarnOnce();
        if (ctx == null) {
            return null;
        }
        if (ctx.eventLoop().inEventLoop()) {
            return ctx.attr(TRACE_CONTEXT_KEY).get();
        } else {
            final TraceContext threadLocalContext = THREAD_LOCAL_CONTEXT.get();
            if (threadLocalContext != null) {
                return threadLocalContext;
            }
            // First span on a non-request thread will use the request's TraceContext as a parent.
            return ctx.attr(TRACE_CONTEXT_KEY).get();
        }
    }

    @Override
    public Scope newScope(@Nullable TraceContext currentSpan) {
        // Handle inspection added to ensure we can fail-fast if this isn't installed.
        if (currentSpan != null && PingPongExtra.maybeSetPong(currentSpan)) {
            return Scope.NOOP;
        }

        final RequestContext ctx = getRequestContextOrWarnOnce();
        if (ctx == null) {
            return INCOMPLETE_CONFIGURATION_SCOPE;
        }

        if (ctx.eventLoop().inEventLoop()) {
            return createScopeForRequestThread(ctx, currentSpan);
        } else {
            // The RequestContext is the canonical thread-local storage for the thread processing the request.
            // However, when creating spans on other threads (e.g., a thread-pool), we must use separate
            // thread-local storage to prevent threads from replacing the same trace context.
            return createScopeForNonRequestThread(currentSpan);
        }
    }

    private static Scope createScopeForRequestThread(RequestContext ctx, @Nullable TraceContext currentSpan) {
        final Attribute<TraceContext> traceContextAttribute = ctx.attr(TRACE_CONTEXT_KEY);

        final TraceContext previous = traceContextAttribute.getAndSet(currentSpan);

        // Don't remove the outer-most context (client or server request)
        if (previous == null) {
            return INITIAL_REQUEST_SCOPE;
        }

        // Removes sub-spans (i.e. local spans) from the current context when Brave's scope does.
        // If an asynchronous sub-span, it may still complete later.
        class RequestContextTraceContextScope implements Scope {
            @Override
            public void close() {
                // re-lookup the attribute to avoid holding a reference to the request if this scope is leaked
                getTraceContextAttributeOrWarnOnce().set(previous);
            }

            @Override
            public String toString() {
                return "RequestContextTraceContextScope";
            }
        }

        return new RequestContextTraceContextScope();
    }

    private static Scope createScopeForNonRequestThread(@Nullable TraceContext currentSpan) {
        final TraceContext previous = THREAD_LOCAL_CONTEXT.get();
        THREAD_LOCAL_CONTEXT.set(currentSpan);
        class ThreadLocalScope implements Scope {
            @Override
            public void close() {
                THREAD_LOCAL_CONTEXT.set(previous);
            }

            @Override
            public String toString() {
                return "ThreadLocalScope";
            }
        }

        return new ThreadLocalScope();
    }

    /** Armeria code should always have a request context available, and this won't work without it. */
    @Nullable
    private static RequestContext getRequestContextOrWarnOnce() {
        return RequestContext.mapCurrent(Function.identity(), LogRequestContextWarningOnce.INSTANCE);
    }

    /** Armeria code should always have a request context available, and this won't work without it. */
    @Nullable private static Attribute<TraceContext> getTraceContextAttributeOrWarnOnce() {
        final RequestContext ctx = getRequestContextOrWarnOnce();
        if (ctx == null) {
            return null;
        }
        return ctx.attr(TRACE_CONTEXT_KEY);
    }

    private enum LogRequestContextWarningOnce implements Supplier<RequestContext> {

        INSTANCE;

        @Override
        @Nullable
        public RequestContext get() {
            ClassLoaderHack.loadMe();
            return null;
        }

        /**
         * This won't be referenced until {@link #get()} is called. If there's only one classloader, the
         * initializer will only be called once.
         */
        static class ClassLoaderHack {
            static void loadMe() {
            }

            static {
                logger.warn("Attempted to propagate trace context, but no request context available. " +
                            "Did you forget to use RequestContext.contextAwareExecutor() or " +
                            "RequestContext.makeContextAware()?");
            }
        }
    }

    /** Hack to allow us to peek inside a current trace context implementation. */
    @VisibleForTesting
    static final class PingPongExtra {
        /**
         * If the input includes only this extra, set {@link #isPong() pong = true}.
         */
        static boolean maybeSetPong(TraceContext context) {
            if (context.extra().size() == 1) {
                Object extra = context.extra().get(0);
                if (extra instanceof PingPongExtra) {
                    ((PingPongExtra) extra).pong = true;
                    return true;
                }
            }
            return false;
        }

        private boolean pong;

        boolean isPong() {
            return pong;
        }
    }
}
