/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.resteasy;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.jboss.resteasy.core.AbstractExecutionContext;
import org.jboss.resteasy.core.ResteasyContext;
import org.jboss.resteasy.core.ResteasyContext.CloseableContext;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.resteasy_jaxrs.i18n.Messages;
import org.jboss.resteasy.spi.ResteasyAsynchronousResponse;
import org.jboss.resteasy.spi.RunnableWithException;

import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.util.concurrent.FastThreadLocalThread;

/**
 * Implements {@link AbstractExecutionContext}.
 */
final class ResteasyAsynchronousExecutionContextImpl extends AbstractExecutionContext {

    private final ResteasyAsynchronousResponseImpl asyncResponse;
    private volatile boolean wasSuspended;

    ResteasyAsynchronousExecutionContextImpl(SynchronousDispatcher dispatcher,
                                             AbstractResteasyHttpRequest request,
                                             ResteasyHttpResponseImpl response) {
        super(dispatcher, request, response);
        asyncResponse = new ResteasyAsynchronousResponseImpl(dispatcher, request, response);
    }

    @Override
    public boolean isSuspended() {
        return wasSuspended;
    }

    @Override
    public ResteasyAsynchronousResponse getAsyncResponse() {
        return asyncResponse;
    }

    /**
     * Suspends client connection for asynchronous request processing.
     * @throws IllegalStateException if it was previously suspended.
     */
    @Override
    public ResteasyAsynchronousResponse suspend() {
        return suspend(-1);
    }

    /**
     * Suspends client connection for asynchronous request processing.
     * @param millis a number of milliseconds to suspend client connection for.
     * @throws IllegalStateException if it was previously suspended.
     */
    @Override
    public ResteasyAsynchronousResponse suspend(long millis) {
        return suspend(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Suspends client connection for asynchronous request processing.
     * @param time an amount of time to suspend client connection for, expressed in provided units.
     * @param unit an {@link TimeUnit} for {@code time} parameter.
     * @throws IllegalStateException if it was previously suspended.
     */
    @Override
    public ResteasyAsynchronousResponse suspend(long time, TimeUnit unit) {
        if (wasSuspended) {
            throw new IllegalStateException(Messages.MESSAGES.alreadySuspended());
        }
        wasSuspended = true;
        return asyncResponse;
    }

    @Override
    public void complete() {
        if (wasSuspended) {
            asyncResponse.complete();
        }
    }

    @Override
    public CompletionStage<Void> executeAsyncIo(CompletionStage<Void> f) {
        // check if this CF is already resolved
        final CompletableFuture<Void> ret = f.toCompletableFuture();
        // if it's not resolved, we may need to suspend
        if (!ret.isDone() && !isSuspended()) {
            suspend();
        }
        return ret;
    }

    @Override
    public CompletionStage<Void> executeBlockingIo(RunnableWithException f,
                                                   boolean hasInterceptors) {
        if (!isIoThread()) {
            // we're blocking
            try {
                f.run();
            } catch (Exception e) {
                final CompletableFuture<Void> ret = new CompletableFuture<>();
                ret.completeExceptionally(e);
                return ret;
            }
            return UnmodifiableFuture.completedFuture(null);
        } else if (!hasInterceptors) {
            final Map<Class<?>, Object> context = ResteasyContext.getContextDataMap();
            // turn any sync request into async
            if (!isSuspended()) {
                suspend();
            }
            return CompletableFuture.runAsync(() -> {
                try (CloseableContext newContext = ResteasyContext.addCloseableContextDataLevel(context)) {
                    f.run();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            final CompletableFuture<Void> ret = new CompletableFuture<>();
            ret.completeExceptionally(new RuntimeException(
                    "Cannot use blocking IO with interceptors when we're on the IO thread"));
            return ret;
        }
    }

    private static boolean isIoThread() {
        return Thread.currentThread() instanceof FastThreadLocalThread;
    }
}
