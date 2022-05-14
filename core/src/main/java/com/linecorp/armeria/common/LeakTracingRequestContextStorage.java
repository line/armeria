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

package com.linecorp.armeria.common;

import static com.linecorp.armeria.internal.common.RequestContextUtil.newIllegalContextPushingException;
import static java.lang.Thread.currentThread;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.concurrent.FastThreadLocal;

/**
 * A {@link RequestContextStorage} which keeps track of {@link RequestContext}s, reporting pushed thread
 * information if a {@link RequestContext} is leaked.
 */
@UnstableApi
public final class LeakTracingRequestContextStorage implements RequestContextStorage {

    private final RequestContextStorage delegate;
    private final FastThreadLocal<PendingRequestContextStackTrace> pendingRequestCtx;
    private final Sampler<Object> sampler;

    /**
     * Creates a new instance.
     * @param delegate the underlying {@link RequestContextStorage} that stores {@link RequestContext}
     */
    public LeakTracingRequestContextStorage(RequestContextStorage delegate) {
        this(delegate, Flags.verboseExceptionSampler());
    }

    /**
     * Creates a new instance.
     * @param delegate the underlying {@link RequestContextStorage} that stores {@link RequestContext}
     * @param sampler the {@link Sampler} that determines whether to retain the stacktrace of the context leaks
     */
    public LeakTracingRequestContextStorage(RequestContextStorage delegate,
                                            Sampler<?> sampler) {
        this.delegate = requireNonNull(delegate, "delegate");
        pendingRequestCtx = new FastThreadLocal<>();
        this.sampler = (Sampler<Object>) requireNonNull(sampler, "sampler");
    }

    @Nullable
    @Override
    public <T extends RequestContext> T push(RequestContext toPush) {
        requireNonNull(toPush, "toPush");

        final RequestContext prevContext = delegate.currentOrNull();
        if (prevContext != null) {
            if (prevContext == toPush) {
                // Re-entrance
            } else if (toPush instanceof ServiceRequestContext &&
                       prevContext.root() == toPush) {
                // The delegate has the ServiceRequestContext whose root() is toPush
            } else if (toPush instanceof ClientRequestContext &&
                       prevContext.root() == toPush.root()) {
                // The delegate has the ClientRequestContext whose root() is the same as toPush.root()
            } else {
                throw newIllegalContextPushingException(prevContext, toPush, pendingRequestCtx.get());
            }
        }

        if (sampler.isSampled(PendingRequestContextStackTrace.class)) {
            pendingRequestCtx.set(new PendingRequestContextStackTrace(toPush, true));
        } else {
            pendingRequestCtx.set(new PendingRequestContextStackTrace(toPush, false));
        }

        return delegate.push(toPush);
    }

    @Override
    public void pop(RequestContext current, @Nullable RequestContext toRestore) {
        try {
            delegate.pop(current, toRestore);
        } finally {
            pendingRequestCtx.remove();
        }
    }

    @Nullable
    @Override
    public <T extends RequestContext> T currentOrNull() {
        return delegate.currentOrNull();
    }

    static class PendingRequestContextStackTrace extends RuntimeException {

        private static final long serialVersionUID = -689451606253441556L;

        PendingRequestContextStackTrace(RequestContext context, boolean isSample) {
            super("At thread [" + currentThread().getName() + "], previous RequestContext didn't popped : " +
                  context + (isSample ? ", It is pushed at the following stacktrace" : ""), null,
                  true, isSample);
        }
    }
}
