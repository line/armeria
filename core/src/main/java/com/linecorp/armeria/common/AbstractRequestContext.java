/*
 * Copyright 2016 LINE Corporation
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

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.DefaultServiceRequestContext;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

/**
 * A skeletal {@link RequestContext} implementation.
 */
public abstract class AbstractRequestContext implements RequestContext {

    private boolean timedOut;

    @Override
    public final EventLoop contextAwareEventLoop() {
        return RequestContext.super.contextAwareEventLoop();
    }

    @Override
    public final Executor makeContextAware(Executor executor) {
        return RequestContext.super.makeContextAware(executor);
    }

    @Override
    public final <T> Callable<T> makeContextAware(Callable<T> callable) {
        return () -> {
            try (SafeCloseable ignored = pushIfAbsent()) {
                return callable.call();
            }
        };
    }

    @Override
    public final Runnable makeContextAware(Runnable runnable) {
        return () -> {
            try (SafeCloseable ignored = pushIfAbsent()) {
                runnable.run();
            }
        };
    }

    @Override
    public final <T, R> Function<T, R> makeContextAware(Function<T, R> function) {
        return t -> {
            try (SafeCloseable ignored = pushIfAbsent()) {
                return function.apply(t);
            }
        };
    }

    @Override
    public final <T, U, V> BiFunction<T, U, V> makeContextAware(BiFunction<T, U, V> function) {
        return (t, u) -> {
            try (SafeCloseable ignored = pushIfAbsent()) {
                return function.apply(t, u);
            }
        };
    }

    @Override
    public final <T> Consumer<T> makeContextAware(Consumer<T> action) {
        return t -> {
            try (SafeCloseable ignored = pushIfAbsent()) {
                action.accept(t);
            }
        };
    }

    @Override
    public final <T, U> BiConsumer<T, U> makeContextAware(BiConsumer<T, U> action) {
        return (t, u) -> {
            try (SafeCloseable ignored = pushIfAbsent()) {
                action.accept(t, u);
            }
        };
    }

    @Override
    public final <T> FutureListener<T> makeContextAware(FutureListener<T> listener) {
        return future -> invokeOperationComplete(listener, future);
    }

    @Override
    public final ChannelFutureListener makeContextAware(ChannelFutureListener listener) {
        return future -> invokeOperationComplete(listener, future);
    }

    @Override
    public final <T extends Future<?>> GenericFutureListener<T>
    makeContextAware(GenericFutureListener<T> listener) {
        return future -> invokeOperationComplete(listener, future);
    }

    @Override
    public final <T> CompletionStage<T> makeContextAware(CompletionStage<T> stage) {
        final CompletableFuture<T> future = new RequestContextAwareCompletableFuture<>(this);
        stage.handle((result, cause) -> {
            try (SafeCloseable ignored = pushIfAbsent()) {
                if (cause != null) {
                    future.completeExceptionally(cause);
                } else {
                    future.complete(result);
                }
            }
            return null;
        });
        return future;
    }

    @Override
    public final <T> CompletableFuture<T> makeContextAware(CompletableFuture<T> future) {
        return RequestContext.super.makeContextAware(future);
    }

    @Override
    public boolean isTimedOut() {
        return timedOut;
    }

    /**
     * Marks this {@link RequestContext} as having been timed out. Any callbacks created with
     * {@code makeContextAware} that are run after this will be failed with {@link CancellationException}.
     *
     * @deprecated Use {@link DefaultServiceRequestContext#setTimedOut()}.
     */
    @Deprecated
    public void setTimedOut() {
        timedOut = true;
    }

    private <T extends Future<?>> void invokeOperationComplete(
            GenericFutureListener<T> listener, T future) throws Exception {
        try (SafeCloseable ignored = pushIfAbsent()) {
            listener.operationComplete(future);
        }
    }

    @Override
    public final void onEnter(Runnable callback) {
        RequestContext.super.onEnter(callback);
    }

    @Override
    public final void onExit(Runnable callback) {
        RequestContext.super.onExit(callback);
    }

    @Override
    public final void resolvePromise(Promise<?> promise, Object result) {
        RequestContext.super.resolvePromise(promise, result);
    }

    @Override
    public final void rejectPromise(Promise<?> promise, Throwable cause) {
        RequestContext.super.rejectPromise(promise, cause);
    }

    @Override
    public final int hashCode() {
        return super.hashCode();
    }

    @Override
    public final boolean equals(Object obj) {
        return super.equals(obj);
    }
}
