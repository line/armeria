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

package com.linecorp.armeria.micrometer.tracing.common;

import static com.linecorp.armeria.micrometer.tracing.internal.TraceContextUtil.setTraceContext;
import static com.linecorp.armeria.micrometer.tracing.internal.TraceContextUtil.traceContext;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;

import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;

/**
 * Tracing context implemented with {@link RequestContext}.
 *
 * <p>This {@link CurrentTraceContext} stores/loads the trace context into/from a
 * {@link RequestContext}'s attribute so that there's no need for thread local variables
 * which can lead to unpredictable behavior in asynchronous programming.
 */
public final class ArmeriaCurrentTraceContext implements CurrentTraceContext {

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
     */
    public static void setCurrentThreadNotRequestThread(boolean value) {
        if (value) {
            THREAD_NOT_REQUEST_THREAD.set(true);
        } else {
            THREAD_NOT_REQUEST_THREAD.remove();
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaCurrentTraceContext.class);

    // Thread-local for storing TraceContext when invoking callbacks off the request thread.
    private static final ThreadLocal<TraceContext> THREAD_LOCAL_CONTEXT = new ThreadLocal<>();

    private static final ThreadLocal<Boolean> THREAD_NOT_REQUEST_THREAD = new ThreadLocal<>();

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

    private final List<Pattern> nonRequestThreadPatterns;

    /**
     * Returns the {@link ArmeriaCurrentTraceContext} singleton instance.
     */
    public static ArmeriaCurrentTraceContext of() {
        return new ArmeriaCurrentTraceContext(Collections.emptyList());
    }

    ArmeriaCurrentTraceContext(List<Pattern> nonRequestThreadPatterns) {
        this.nonRequestThreadPatterns = nonRequestThreadPatterns;
    }

    @Nullable
    @Override
    public TraceContext context() {
        final RequestContext ctx = getRequestContextOrWarnOnce();
        if (ctx == null) {
            return THREAD_LOCAL_CONTEXT.get();
        }

        if (ctx.eventLoop().inEventLoop()) {
            return traceContext(ctx);
        } else {
            final TraceContext threadLocalContext = THREAD_LOCAL_CONTEXT.get();
            if (threadLocalContext != null) {
                return threadLocalContext;
            }
            // First span on a non-request thread will use the request's TraceContext as a parent.
            return traceContext(ctx);
        }
    }

    @Override
    public Scope newScope(@Nullable TraceContext currentSpan) {
        final RequestContext ctx = getRequestContextOrWarnOnce();
        if (ctx != null && ctx.eventLoop().inEventLoop()) {
            return createScopeForRequestThread(ctx, currentSpan);
        } else {
            // The RequestContext is the canonical thread-local storage for the thread processing the request.
            // However, when creating spans on other threads (e.g., a thread-pool), we must use separate
            // thread-local storage to prevent threads from replacing the same trace context.
            return createScopeForNonRequestThread(currentSpan);
        }
    }

    @Override
    public Scope maybeScope(TraceContext context) {
        final TraceContext current = context();
        if (Objects.equals(current, context)) {
            return () -> {};
        }
        return newScope(context);
    }

    @Override
    public <C> Callable<C> wrap(Callable<C> task) {
        return RequestContext.current().makeContextAware(task);
    }

    @Override
    public Runnable wrap(Runnable task) {
        return RequestContext.current().makeContextAware(task);
    }

    @Override
    public Executor wrap(Executor delegate) {
        return RequestContext.current().makeContextAware(delegate);
    }

    @Override
    public ExecutorService wrap(ExecutorService delegate) {
        return RequestContext.current().makeContextAware(delegate);
    }

    private Scope createScopeForRequestThread(RequestContext ctx, @Nullable TraceContext currentSpan) {
        final TraceContext previous = traceContext(ctx);
        setTraceContext(ctx, currentSpan);

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
                final RequestContext ctx = getRequestContextOrWarnOnce();
                if (ctx != null) {
                    setTraceContext(ctx, previous);
                }
            }

            @Override
            public String toString() {
                return "RequestContextTraceContextScope";
            }
        }

        return new RequestContextTraceContextScope();
    }

    private Scope createScopeForNonRequestThread(@Nullable TraceContext currentSpan) {
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

    /**
     * Armeria code should always have a request context available, and this won't work without it.
     */
    @Nullable
    private RequestContext getRequestContextOrWarnOnce() {
        if (Boolean.TRUE.equals(THREAD_NOT_REQUEST_THREAD.get())) {
            return null;
        }
        if (!nonRequestThreadPatterns.isEmpty()) {
            final String threadName = Thread.currentThread().getName();
            for (Pattern pattern : nonRequestThreadPatterns) {
                if (pattern.matcher(threadName).find()) {
                    // A matched thread will match forever, so it's worth avoiding this regex match on every
                    // time the thread is used by saving into the ThreadLocal.
                    setCurrentThreadNotRequestThread(true);
                    return null;
                }
            }
        }
        return RequestContext.mapCurrent(Function.identity(), LogRequestContextWarningOnce.INSTANCE);
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
        private static final class ClassLoaderHack {
            static void loadMe() {}

            static {
                logger.warn("Attempted to propagate trace context, but no request context available. " +
                            "Did you forget to use RequestContext.makeContextAware()?",
                            new NoRequestContextException());
            }
        }

        private static final class NoRequestContextException extends RuntimeException {
            private static final long serialVersionUID = 2804189311774982052L;
        }
    }
}
