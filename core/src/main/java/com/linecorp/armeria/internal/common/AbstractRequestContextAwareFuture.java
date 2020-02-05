/*
 * Copyright 2020 LINE Corporation
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

import static com.linecorp.armeria.internal.common.util.EventLoopCheckingUtil.maybeLogIfOnEventLoop;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

/**
 * A base class for {@link CompletableFuture} which pushing {@link RequestContext} into the thread-local
 * when executes callbacks.
 */
public abstract class AbstractRequestContextAwareFuture<T> extends CompletableFuture<T> {

    private static final Logger logger =
            LoggerFactory.getLogger(AbstractRequestContextAwareFuture.class);

    private final RequestContext ctx;

    protected AbstractRequestContextAwareFuture(RequestContext ctx) {
        this.ctx = ctx;
    }

    protected RequestContext ctx() {
        return ctx;
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        maybeLogIfOnEventLoop();
        return super.get();
    }

    @Override
    public T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        maybeLogIfOnEventLoop();
        return super.get(timeout, unit);
    }

    @Override
    public T join() {
        maybeLogIfOnEventLoop();
        return super.join();
    }

    protected Runnable makeContextAwareLoggingException(Runnable action) {
        requireNonNull(action, "action");
        return () -> {
            makeContextAwareLoggingException0(action);
        };
    }

    protected <I> Consumer<I> makeContextAwareLoggingException(Consumer<I> action) {
        requireNonNull(action, "action");
        return t -> makeContextAwareLoggingException0(() -> action.accept(t));
    }

    protected <I, U> BiConsumer<I, U> makeContextAwareLoggingException(BiConsumer<I, U> action) {
        requireNonNull(action, "action");
        return (t, u) -> makeContextAwareLoggingException0(() -> action.accept(t, u));
    }

    protected <V> Supplier<V> makeContextAwareLoggingException(Supplier<? extends V> supplier) {
        requireNonNull(supplier, "supplier");
        return () -> makeContextAwareLoggingException0(supplier);
    }

    protected <I, R> Function<I, R> makeContextAwareLoggingException(Function<I, R> fn) {
        requireNonNull(fn, "fn");
        return t -> makeContextAwareLoggingException0(() -> fn.apply(t));
    }

    protected <I, U, V> BiFunction<I, U, V> makeContextAwareLoggingException(BiFunction<I, U, V> fn) {
        requireNonNull(fn, "fn");
        return (t, u) -> makeContextAwareLoggingException0(() -> fn.apply(t, u));
    }

    private void makeContextAwareLoggingException0(Runnable action) {
        final SafeCloseable handle;
        try {
            handle = ctx.push();
        } catch (Throwable th) {
            logger.warn("An error occurred when pushing a context", th);
            throw th;
        }

        try {
            action.run();
        } finally {
            handle.close();
        }
    }

    private <V> V makeContextAwareLoggingException0(Supplier<? extends V> fn) {
        final SafeCloseable handle;
        try {
            handle = ctx.push();
        } catch (Throwable th) {
            logger.warn("An error occurred when pushing a context", th);
            throw th;
        }

        try {
            return fn.get();
        } finally {
            handle.close();
        }
    }
}
