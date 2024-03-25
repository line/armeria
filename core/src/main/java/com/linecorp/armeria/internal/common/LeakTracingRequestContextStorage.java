/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import static java.lang.Thread.currentThread;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextWrapper;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
import com.linecorp.armeria.common.RequestContextWrapper;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContextWrapper;

/**
 * A {@link RequestContextStorage} which keeps track of {@link RequestContext}s, reporting pushed thread
 * information if a {@link RequestContext} is leaked.
 */
final class LeakTracingRequestContextStorage implements RequestContextStorage {

    private final RequestContextStorage delegate;
    private final Sampler<? super RequestContext> sampler;

    /**
     * Creates a new instance.
     *
     * @param delegate the underlying {@link RequestContextStorage} that stores {@link RequestContext}
     * @param sampler the {@link Sampler} that determines whether to retain the stacktrace of the context leaks
     */
    LeakTracingRequestContextStorage(RequestContextStorage delegate,
                                     Sampler<? super RequestContext> sampler) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.sampler = requireNonNull(sampler, "sampler");
    }

    @Nullable
    @Override
    public <T extends RequestContext> T push(RequestContext toPush) {
        requireNonNull(toPush, "toPush");
        if (sampler.isSampled(toPush)) {
            return delegate.push(wrapRequestContext(unwrapTraceableRequestContext(toPush)));
        }
        return delegate.push(toPush);
    }

    private static RequestContext unwrapTraceableRequestContext(RequestContext ctx) {
        while (true) {
            final RequestContext unwrapped = ctx.unwrap();
            if (!(unwrapped instanceof TraceableRequestContext)) {
                return unwrapped;
            }
            ctx = unwrapped;
        }
    }

    @Override
    public void pop(RequestContext current, @Nullable RequestContext toRestore) {
        requireNonNull(current, "current");
        delegate.pop(current, toRestore);
    }

    @Nullable
    @Override
    public <T extends RequestContext> T currentOrNull() {
        return delegate.currentOrNull();
    }

    @Override
    public RequestContextStorage unwrap() {
        return delegate;
    }

    private static RequestContextWrapper<?> wrapRequestContext(RequestContext ctx) {
        if (ctx instanceof ClientRequestContext) {
            return new TraceableClientRequestContext((ClientRequestContext) ctx);
        }
        assert ctx instanceof ServiceRequestContext;
        return new TraceableServiceRequestContext((ServiceRequestContext) ctx);
    }

    private static String stacktraceToString(StackTraceElement[] stackTrace,
                                             RequestContext unwrap) {
        final StringBuilder builder = new StringBuilder(512);
        builder.append(unwrap).append(System.lineSeparator());
        for (int i = 1; i < stackTrace.length; i++) {
            builder.append("\tat ").append(stackTrace[i]).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private interface TraceableRequestContext {}

    private static final class TraceableClientRequestContext
            extends ClientRequestContextWrapper implements TraceableRequestContext {

        private final StackTraceElement[] stackTrace;

        private TraceableClientRequestContext(ClientRequestContext delegate) {
            super(delegate);
            stackTrace = currentThread().getStackTrace();
        }

        @Override
        public String toString() {
            return stacktraceToString(stackTrace, unwrap());
        }
    }

    private static final class TraceableServiceRequestContext
            extends ServiceRequestContextWrapper implements TraceableRequestContext {

        private final StackTraceElement[] stackTrace;

        private TraceableServiceRequestContext(ServiceRequestContext delegate) {
            super(delegate);
            stackTrace = currentThread().getStackTrace();
        }

        @Override
        public String toString() {
            return stacktraceToString(stackTrace, unwrap());
        }
    }
}
