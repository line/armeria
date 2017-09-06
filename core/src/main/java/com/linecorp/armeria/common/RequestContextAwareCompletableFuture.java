/*
 * Copyright 2017 LINE Corporation
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

final class RequestContextAwareCompletableFuture<T> extends CompletableFuture<T> {

    private final RequestContext ctx;

    RequestContextAwareCompletableFuture(RequestContext requestContext) {
        ctx = requestContext;
    }

    @Override
    public <U> CompletableFuture<U> thenApply(Function<? super T,? extends U> fn) {
        return ctx.makeContextAware(super.thenApply(ctx.makeContextAware(fn)));
    }

    @Override
    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T,? extends U> fn) {
        return ctx.makeContextAware(super.thenApplyAsync(ctx.makeContextAware(fn)));
    }

    @Override
    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T,? extends U> fn, Executor executor) {
        return ctx.makeContextAware(super.thenApplyAsync(ctx.makeContextAware(fn), executor));
    }

    @Override
    public CompletableFuture<Void> thenAccept(Consumer<? super T> action) {
        return ctx.makeContextAware(super.thenAccept(ctx.makeContextAware(action)));
    }

    @Override
    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
        return ctx.makeContextAware(super.thenAcceptAsync(ctx.makeContextAware(action)));
    }

    @Override
    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        return ctx.makeContextAware(super.thenAcceptAsync(ctx.makeContextAware(action), executor));
    }

    @Override
    public CompletableFuture<Void> thenRun(Runnable action) {
        return ctx.makeContextAware(super.thenRun(ctx.makeContextAware(action)));
    }

    @Override
    public CompletableFuture<Void> thenRunAsync(Runnable action) {
        return ctx.makeContextAware(super.thenRunAsync(ctx.makeContextAware(action)));
    }

    @Override
    public CompletableFuture<Void> thenRunAsync(Runnable action, Executor executor) {
        return ctx.makeContextAware(super.thenRunAsync(ctx.makeContextAware(action), executor));
    }

    @Override
    public <U,V> CompletableFuture<V> thenCombine(CompletionStage<? extends U> other,
                                                  BiFunction<? super T,? super U,? extends V> fn) {
        return ctx.makeContextAware(super.thenCombine(other, ctx.makeContextAware(fn)));
    }

    @Override
    public <U,V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
                                                       BiFunction<? super T,? super U,? extends V> fn) {
        return ctx.makeContextAware(super.thenCombineAsync(other, ctx.makeContextAware(fn)));
    }

    @Override
    public <U,V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
                                                       BiFunction<? super T,? super U,? extends V> fn,
                                                       Executor executor) {
        return ctx.makeContextAware(super.thenCombineAsync(other, ctx.makeContextAware(fn), executor));
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBoth(CompletionStage<? extends U> other,
                                                      BiConsumer<? super T, ? super U> action) {
        return ctx.makeContextAware(super.thenAcceptBoth(other, ctx.makeContextAware(action)));
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
                                                           BiConsumer<? super T, ? super U> action) {
        return ctx.makeContextAware(super.thenAcceptBothAsync(other, ctx.makeContextAware(action)));
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
                                                           BiConsumer<? super T, ? super U> action,
                                                           Executor executor) {
        return ctx.makeContextAware(super.thenAcceptBothAsync(other, ctx.makeContextAware(action), executor));
    }

    @Override
    public CompletableFuture<Void> runAfterBoth(CompletionStage<?> other,
                                                Runnable action) {
        return ctx.makeContextAware(super.runAfterBoth(other, ctx.makeContextAware(action)));
    }

    @Override
    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other,
                                                     Runnable action) {
        return ctx.makeContextAware(super.runAfterBothAsync(other, ctx.makeContextAware(action)));
    }

    @Override
    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other,
                                                     Runnable action,
                                                     Executor executor) {
        return ctx.makeContextAware(super.runAfterBothAsync(other, ctx.makeContextAware(action), executor));
    }

    @Override
    public <U> CompletableFuture<U> applyToEither(CompletionStage<? extends T> other,
                                                  Function<? super T, U> fn) {
        return ctx.makeContextAware(super.applyToEither(other, ctx.makeContextAware(fn)));
    }

    @Override
    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other,
                                                       Function<? super T, U> fn) {
        return ctx.makeContextAware(super.applyToEitherAsync(other, ctx.makeContextAware(fn)));
    }

    @Override
    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other,
                                                       Function<? super T, U> fn,
                                                       Executor executor) {
        return ctx.makeContextAware(super.applyToEitherAsync(other, ctx.makeContextAware(fn), executor));
    }

    @Override
    public CompletableFuture<Void> acceptEither(CompletionStage<? extends T> other,
                                                Consumer<? super T> action) {
        return ctx.makeContextAware(super.acceptEither(other, ctx.makeContextAware(action)));
    }

    @Override
    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other,
                                                     Consumer<? super T> action) {
        return ctx.makeContextAware(super.acceptEitherAsync(other, ctx.makeContextAware(action)));
    }

    @Override
    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other,
                                                     Consumer<? super T> action,
                                                     Executor executor) {
        return ctx.makeContextAware(super.acceptEitherAsync(other, ctx.makeContextAware(action), executor));
    }

    @Override
    public CompletableFuture<Void> runAfterEither(CompletionStage<?> other,
                                                  Runnable action) {
        return ctx.makeContextAware(super.runAfterEither(other, ctx.makeContextAware(action)));
    }

    @Override
    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other,
                                                       Runnable action) {
        return ctx.makeContextAware(super.runAfterEitherAsync(other, ctx.makeContextAware(action)));
    }

    @Override
    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other,
                                                       Runnable action,
                                                       Executor executor) {
        return ctx.makeContextAware(super.runAfterEitherAsync(other, ctx.makeContextAware(action), executor));
    }

    @Override
    public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
        return ctx.makeContextAware(super.thenCompose(ctx.makeContextAware(fn)));
    }

    @Override
    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {
        return ctx.makeContextAware(super.thenComposeAsync(ctx.makeContextAware(fn)));
    }

    @Override
    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn,
                                                     Executor executor) {
        return ctx.makeContextAware(super.thenComposeAsync(ctx.makeContextAware(fn), executor));
    }

    @Override
    public CompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return ctx.makeContextAware(super.whenComplete(ctx.makeContextAware(action)));
    }

    @Override
    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return ctx.makeContextAware(super.whenCompleteAsync(ctx.makeContextAware(action)));
    }

    @Override
    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action,
                                                  Executor executor) {
        return ctx.makeContextAware(super.whenCompleteAsync(ctx.makeContextAware(action), executor));
    }

    @Override
    public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
        return ctx.makeContextAware(super.handle(ctx.makeContextAware(fn)));
    }

    @Override
    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
        return ctx.makeContextAware(super.handleAsync(ctx.makeContextAware(fn)));
    }

    @Override
    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn,
                                                Executor executor) {
        return ctx.makeContextAware(super.handleAsync(ctx.makeContextAware(fn), executor));
    }

    @Override
    public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
        return ctx.makeContextAware(super.exceptionally(ctx.makeContextAware(fn)));
    }
}
