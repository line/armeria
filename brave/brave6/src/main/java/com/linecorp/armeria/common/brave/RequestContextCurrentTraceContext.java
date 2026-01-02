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

import static com.linecorp.armeria.internal.common.brave.TraceContextUtil.traceContext;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.client.brave.BraveClient;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.brave.InternalTraceContextUtil;
import com.linecorp.armeria.internal.common.brave.TraceContextUtil;
import com.linecorp.armeria.server.brave.BraveService;

import brave.Tracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.TraceContext;

/**
 * {@linkplain Tracing.Builder#currentTraceContext(CurrentTraceContext) Tracing
 * context} implemented with {@link RequestContext}.
 *
 * <p>This {@link CurrentTraceContext} stores/loads the trace context into/from a
 * {@link RequestContext}'s attribute so that there's no need for thread local variables
 * which can lead to unpredictable behavior in asynchronous programming.
 */
public final class RequestContextCurrentTraceContext extends CurrentTraceContext {

    /**
     * Returns the default {@link CurrentTraceContext}. Use this when building a {@link Tracing} instance
     * for use with {@link BraveService} or {@link BraveClient}.
     *
     * <p>If you need to customize the context, use {@link #builder()} instead.
     *
     * @see Tracing.Builder#currentTraceContext(CurrentTraceContext)
     */
    public static RequestContextCurrentTraceContext ofDefault() {
        return DEFAULT;
    }

    /**
     * Use this when you need customizations such as log integration via
     * {@linkplain RequestContextCurrentTraceContextBuilder#addScopeDecorator(ScopeDecorator)}.
     *
     * @see Tracing.Builder#currentTraceContext(CurrentTraceContext)
     */
    public static RequestContextCurrentTraceContextBuilder builder() {
        return new RequestContextCurrentTraceContextBuilder();
    }

    /**
     * Sets whether the current thread is not a request thread, meaning it is never executed in the scope of a
     * server or client request and will never have a {@link RequestContext} available. This can be called from
     * background threads, such as the thread that reports traced spans to storage, to prevent logging a
     * warning when trying to start a trace without having a {@link RequestContext}.
     *
     * <p>For example, you could prevent warnings from the administrative threads controlled by
     * a {@link java.util.concurrent.ThreadFactory} like the following:
     * <pre>{@code
     * > ThreadFactory factory = (runnable) -> new Thread(new Runnable() {
     * >     @Override
     * >     public void run() {
     * >         RequestContextCurrentTraceContext.setCurrentThreadNotRequestThread(true);
     * >         runnable.run();
     * >     }
     * >
     * >     @Override
     * >     public String toString() {
     * >         return runnable.toString();
     * >     }
     * > });
     * }</pre>
     *
     * @deprecated This setting has no effect.
     */
    @Deprecated
    public static void setCurrentThreadNotRequestThread(boolean value) {
    }

    private static final RequestContextCurrentTraceContext DEFAULT = builder().build();

    private static final Scope NOOP_SCOPE = new Scope() {
        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return "ArmeriaNoopScope";
        }
    };

    private final boolean scopeDecoratorAdded;

    RequestContextCurrentTraceContext(CurrentTraceContext.Builder builder, boolean scopeDecoratorAdded) {
        super(builder);
        this.scopeDecoratorAdded = scopeDecoratorAdded;
    }

    @Override
    @Nullable
    public TraceContext get() {
        final TraceContext traceContext = InternalTraceContextUtil.get();
        if (traceContext != null) {
            return traceContext;
        }
        final RequestContext ctx = RequestContext.currentOrNull();
        if (ctx == null) {
            return null;
        }
        return traceContext(ctx);
    }

    @Override
    public Scope newScope(@Nullable TraceContext currentSpan) {
        // Handle inspection added to ensure we can fail-fast if this isn't installed.
        if (currentSpan != null && TraceContextUtil.PingPongExtra.maybeSetPong(currentSpan)) {
            return Scope.NOOP;
        }

        final TraceContext threadPrev = InternalTraceContextUtil.get();
        if (threadPrev == currentSpan) {
            // a custom noop scope is used to avoid special behavior in built-in scope decorators
            return decorateScope(currentSpan, NOOP_SCOPE);
        }

        InternalTraceContextUtil.set(currentSpan);

        class ThreadLocalContextScope implements Scope {
            @Override
            public void close() {
                InternalTraceContextUtil.set(threadPrev);
            }

            @Override
            public String toString() {
                return "ThreadLocalScope";
            }
        }

        return decorateScope(currentSpan, new ThreadLocalContextScope());
    }

    @UnstableApi
    @Override
    public Scope decorateScope(@Nullable TraceContext context, Scope scope) {
        // If a `Scope` is decorated, `ScopeDecorator`s populate some contexts as such as MDC, which are stored
        // to a thread-local. The activated contexts will be removed when `decoratedScope.close()` is called.
        // If `Scope.NOOP` is specified, CurrentTraceContext.decorateScope() performs nothing.
        return super.decorateScope(context, scope);
    }

    /**
     * Returns whether this {@link RequestContextCurrentTraceContext} is built with {@link ScopeDecorator}s.
     *
     * @see RequestContextCurrentTraceContextBuilder#addScopeDecorator(ScopeDecorator)
     */
    @UnstableApi
    public boolean scopeDecoratorAdded() {
        return scopeDecoratorAdded;
    }
}
