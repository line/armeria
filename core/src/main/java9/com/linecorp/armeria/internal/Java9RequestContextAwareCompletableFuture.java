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

package com.linecorp.armeria.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

class Java9RequestContextAwareCompletableFuture<T> extends CompletableFuture<T> {

    private final RequestContext ctx;

    Java9RequestContextAwareCompletableFuture(RequestContext requestContext) {
        ctx = requestContext;
    }

    @Override
    public <U> CompletableFuture<U> thenApply(Function<? super T,? extends U> fn) {
        return super.thenApply(ctx.makeContextAware(fn));
    }

    @Override
    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T,? extends U> fn) {
        return super.thenApplyAsync(ctx.makeContextAware(fn));
    }

    @Override
    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T,? extends U> fn, Executor executor) {
        return super.thenApplyAsync(ctx.makeContextAware(fn), executor);
    }

    @Override
    public CompletableFuture<Void> thenAccept(Consumer<? super T> action) {
        return super.thenAccept(ctx.makeContextAware(action));
    }

    @Override
    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
        return super.thenAcceptAsync(ctx.makeContextAware(action));
    }

    @Override
    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        return super.thenAcceptAsync(ctx.makeContextAware(action), executor);
    }

    @Override
    public CompletableFuture<Void> thenRun(Runnable action) {
        return super.thenRun(ctx.makeContextAware(action));
    }

    @Override
    public CompletableFuture<Void> thenRunAsync(Runnable action) {
        return super.thenRunAsync(ctx.makeContextAware(action));
    }

    @Override
    public CompletableFuture<Void> thenRunAsync(Runnable action, Executor executor) {
        return super.thenRunAsync(ctx.makeContextAware(action), executor);
    }

    @Override
    public <U,V> CompletableFuture<V> thenCombine(CompletionStage<? extends U> other,
                                                  BiFunction<? super T,? super U,? extends V> fn) {
        return super.thenCombine(other, ctx.makeContextAware(fn));
    }

    @Override
    public <U,V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
                                                       BiFunction<? super T,? super U,? extends V> fn) {
        return super.thenCombineAsync(other, ctx.makeContextAware(fn));
    }

    @Override
    public <U,V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
                                                       BiFunction<? super T,? super U,? extends V> fn,
                                                       Executor executor) {
        return super.thenCombineAsync(other, ctx.makeContextAware(fn), executor);
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBoth(CompletionStage<? extends U> other,
                                                      BiConsumer<? super T, ? super U> action) {
        return super.thenAcceptBoth(other, ctx.makeContextAware(action));
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
                                                           BiConsumer<? super T, ? super U> action) {
        return super.thenAcceptBothAsync(other, ctx.makeContextAware(action));
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
                                                           BiConsumer<? super T, ? super U> action,
                                                           Executor executor) {
        return super.thenAcceptBothAsync(other, ctx.makeContextAware(action), executor);
    }

    @Override
    public CompletableFuture<Void> runAfterBoth(CompletionStage<?> other,
                                                Runnable action) {
        return super.runAfterBoth(other, ctx.makeContextAware(action));
    }

    @Override
    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other,
                                                     Runnable action) {
        return super.runAfterBothAsync(other, ctx.makeContextAware(action));
    }

    @Override
    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other,
                                                     Runnable action,
                                                     Executor executor) {
        return super.runAfterBothAsync(other, ctx.makeContextAware(action), executor);
    }

    @Override
    public <U> CompletableFuture<U> applyToEither(CompletionStage<? extends T> other,
                                                  Function<? super T, U> fn) {
        return super.applyToEither(other, ctx.makeContextAware(fn));
    }

    @Override
    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other,
                                                       Function<? super T, U> fn) {
        return super.applyToEitherAsync(other, ctx.makeContextAware(fn));
    }

    @Override
    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other,
                                                       Function<? super T, U> fn,
                                                       Executor executor) {
        return super.applyToEitherAsync(other, ctx.makeContextAware(fn), executor);
    }

    @Override
    public CompletableFuture<Void> acceptEither(CompletionStage<? extends T> other,
                                                Consumer<? super T> action) {
        return super.acceptEither(other, ctx.makeContextAware(action));
    }

    @Override
    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other,
                                                     Consumer<? super T> action) {
        return super.acceptEitherAsync(other, ctx.makeContextAware(action));
    }

    @Override
    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other,
                                                     Consumer<? super T> action,
                                                     Executor executor) {
        return super.acceptEitherAsync(other, ctx.makeContextAware(action), executor);
    }

    @Override
    public CompletableFuture<Void> runAfterEither(CompletionStage<?> other,
                                                  Runnable action) {
        return super.runAfterEither(other, ctx.makeContextAware(action));
    }

    @Override
    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other,
                                                       Runnable action) {
        return super.runAfterEitherAsync(other, ctx.makeContextAware(action));
    }

    @Override
    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other,
                                                       Runnable action,
                                                       Executor executor) {
        return super.runAfterEitherAsync(other, ctx.makeContextAware(action), executor);
    }

    @Override
    public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
        return super.thenCompose(ctx.makeContextAware(fn));
    }

    @Override
    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {
        return super.thenComposeAsync(ctx.makeContextAware(fn));
    }

    @Override
    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn,
                                                     Executor executor) {
        return super.thenComposeAsync(ctx.makeContextAware(fn), executor);
    }

    @Override
    public CompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return super.whenComplete(ctx.makeContextAware(action));
    }

    @Override
    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return super.whenCompleteAsync(ctx.makeContextAware(action));
    }

    @Override
    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action,
                                                  Executor executor) {
        return super.whenCompleteAsync(ctx.makeContextAware(action), executor);
    }

    @Override
    public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
        return super.handle(ctx.makeContextAware(fn));
    }

    @Override
    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
        return super.handleAsync(ctx.makeContextAware(fn));
    }

    @Override
    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn,
                                                Executor executor) {
        return super.handleAsync(ctx.makeContextAware(fn), executor);
    }

    @Override
    public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
        return super.exceptionally(ctx.makeContextAware(fn));
    }

    @Override
    public <U> CompletableFuture<U> newIncompleteFuture() {
        return new Java9RequestContextAwareCompletableFuture<>(ctx);
    }

    @Override
    public CompletionStage<T> minimalCompletionStage() {
        return new Java9RequestContextAwareMinimalStage<>(this);
    }

    @Override
    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
        return super.completeAsync(makeContextAware(supplier));
    }

    @Override
    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier, Executor executor) {
        return super.completeAsync(makeContextAware(supplier), executor);
    }

    private Supplier<T> makeContextAware(Supplier<? extends T> action) {
        return () -> {
            try (SafeCloseable ignored = ctx.pushIfAbsent()) {
                return action.get();
            }
        };
    }
}
