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

package com.linecorp.armeria.internal.common;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.common.RequestContext;

final class Java12ContextAwareFuture<T> extends AbstractContextAwareFuture<T> {

    Java12ContextAwareFuture(RequestContext requestContext) {
        super(requestContext);
    }

    @Override
    public <U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> fn) {
        return super.thenApply(makeContextAwareLoggingException(fn));
    }

    @Override
    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn) {
        return super.thenApplyAsync(makeContextAwareLoggingException(fn));
    }

    @Override
    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) {
        requireNonNull(executor, "executor");
        return super.thenApplyAsync(makeContextAwareLoggingException(fn), executor);
    }

    @Override
    public CompletableFuture<Void> thenAccept(Consumer<? super T> action) {
        return super.thenAccept(makeContextAwareLoggingException(action));
    }

    @Override
    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
        return super.thenAcceptAsync(makeContextAwareLoggingException(action));
    }

    @Override
    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        requireNonNull(executor, "executor");
        return super.thenAcceptAsync(makeContextAwareLoggingException(action), executor);
    }

    @Override
    public CompletableFuture<Void> thenRun(Runnable action) {
        return super.thenRun(makeContextAwareLoggingException(action));
    }

    @Override
    public CompletableFuture<Void> thenRunAsync(Runnable action) {
        return super.thenRunAsync(makeContextAwareLoggingException(action));
    }

    @Override
    public CompletableFuture<Void> thenRunAsync(Runnable action, Executor executor) {
        requireNonNull(executor, "executor");
        return super.thenRunAsync(makeContextAwareLoggingException(action), executor);
    }

    @Override
    public <U, V> CompletableFuture<V> thenCombine(CompletionStage<? extends U> other,
                                                   BiFunction<? super T, ? super U, ? extends V> fn) {
        requireNonNull(other, "other");
        return super.thenCombine(other, makeContextAwareLoggingException(fn));
    }

    @Override
    public <U, V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
                                                        BiFunction<? super T, ? super U, ? extends V> fn) {
        requireNonNull(other, "other");
        return super.thenCombineAsync(other, makeContextAwareLoggingException(fn));
    }

    @Override
    public <U, V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
                                                        BiFunction<? super T, ? super U, ? extends V> fn,
                                                        Executor executor) {
        requireNonNull(other, "other");
        requireNonNull(executor, "executor");
        return super.thenCombineAsync(other, makeContextAwareLoggingException(fn), executor);
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBoth(CompletionStage<? extends U> other,
                                                      BiConsumer<? super T, ? super U> action) {
        requireNonNull(other, "other");
        return super.thenAcceptBoth(other, makeContextAwareLoggingException(action));
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
                                                           BiConsumer<? super T, ? super U> action) {
        requireNonNull(other, "other");
        return super.thenAcceptBothAsync(other, makeContextAwareLoggingException(action));
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
                                                           BiConsumer<? super T, ? super U> action,
                                                           Executor executor) {
        requireNonNull(other, "other");
        requireNonNull(executor, "executor");
        return super.thenAcceptBothAsync(other, makeContextAwareLoggingException(action), executor);
    }

    @Override
    public CompletableFuture<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
        requireNonNull(other, "other");
        return super.runAfterBoth(other, makeContextAwareLoggingException(action));
    }

    @Override
    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {
        requireNonNull(other, "other");
        return super.runAfterBothAsync(other, makeContextAwareLoggingException(action));
    }

    @Override
    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other,
                                                     Runnable action,
                                                     Executor executor) {
        requireNonNull(other, "other");
        requireNonNull(executor, "executor");
        return super.runAfterBothAsync(other, makeContextAwareLoggingException(action), executor);
    }

    @Override
    public <U> CompletableFuture<U> applyToEither(CompletionStage<? extends T> other,
                                                  Function<? super T, U> fn) {
        requireNonNull(other, "other");
        return super.applyToEither(other, makeContextAwareLoggingException(fn));
    }

    @Override
    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other,
                                                       Function<? super T, U> fn) {
        requireNonNull(other, "other");
        return super.applyToEitherAsync(other, makeContextAwareLoggingException(fn));
    }

    @Override
    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other,
                                                       Function<? super T, U> fn,
                                                       Executor executor) {
        requireNonNull(other, "other");
        requireNonNull(executor, "executor");
        return super.applyToEitherAsync(other, makeContextAwareLoggingException(fn), executor);
    }

    @Override
    public CompletableFuture<Void> acceptEither(CompletionStage<? extends T> other,
                                                Consumer<? super T> action) {
        requireNonNull(other, "other");
        return super.acceptEither(other, makeContextAwareLoggingException(action));
    }

    @Override
    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other,
                                                     Consumer<? super T> action) {
        requireNonNull(other, "other");
        return super.acceptEitherAsync(other, makeContextAwareLoggingException(action));
    }

    @Override
    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other,
                                                     Consumer<? super T> action,
                                                     Executor executor) {
        requireNonNull(other, "other");
        requireNonNull(executor, "executor");
        return super.acceptEitherAsync(other, makeContextAwareLoggingException(action), executor);
    }

    @Override
    public CompletableFuture<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
        requireNonNull(other, "other");
        return super.runAfterEither(other, makeContextAwareLoggingException(action));
    }

    @Override
    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
        requireNonNull(other, "other");
        return super.runAfterEitherAsync(other, makeContextAwareLoggingException(action));
    }

    @Override
    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other,
                                                       Runnable action,
                                                       Executor executor) {
        requireNonNull(other, "other");
        requireNonNull(executor, "executor");
        return super.runAfterEitherAsync(other, makeContextAwareLoggingException(action), executor);
    }

    @Override
    public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
        return super.thenCompose(makeContextAwareLoggingException(fn));
    }

    @Override
    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {
        return super.thenComposeAsync(makeContextAwareLoggingException(fn));
    }

    @Override
    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn,
                                                     Executor executor) {
        requireNonNull(executor, "executor");
        return super.thenComposeAsync(makeContextAwareLoggingException(fn), executor);
    }

    @Override
    public CompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return super.whenComplete(makeContextAwareLoggingException(action));
    }

    @Override
    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return super.whenCompleteAsync(makeContextAwareLoggingException(action));
    }

    @Override
    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action,
                                                  Executor executor) {
        requireNonNull(executor, "executor");
        return super.whenCompleteAsync(makeContextAwareLoggingException(action), executor);
    }

    @Override
    public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
        return super.handle(makeContextAwareLoggingException(fn));
    }

    @Override
    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
        return super.handleAsync(makeContextAwareLoggingException(fn));
    }

    @Override
    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn,
                                                Executor executor) {
        requireNonNull(executor, "executor");
        return super.handleAsync(makeContextAwareLoggingException(fn), executor);
    }

    @Override
    public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
        return super.exceptionally(makeContextAwareLoggingException(fn));
    }

    @Override
    public CompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn) {
        return context().makeContextAware(super.exceptionallyAsync(makeContextAwareLoggingException(fn)));
    }

    @Override
    public CompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn, Executor executor) {
        requireNonNull(executor, "executor");
        return context().makeContextAware(
            super.exceptionallyAsync(this.makeContextAwareLoggingException(fn), executor));
    }

    @Override
    public CompletableFuture<T> exceptionallyCompose(Function<Throwable, ? extends CompletionStage<T>> fn) {
        return context().makeContextAware(
            super.exceptionallyCompose(makeContextAwareLoggingException(fn)));
    }

    @Override
    public CompletableFuture<T> exceptionallyComposeAsync(
        Function<Throwable, ? extends CompletionStage<T>> fn) {
        return context().makeContextAware(
            super.exceptionallyComposeAsync(makeContextAwareLoggingException(fn)));
    }

    @Override
    public CompletableFuture<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> fn,
                                                          Executor executor) {
        requireNonNull(executor, "executor");
        return context().makeContextAware(
            super.exceptionallyComposeAsync(makeContextAwareLoggingException(fn), executor));
    }

    @Override
    public <U> CompletableFuture<U> newIncompleteFuture() {
        return new Java12ContextAwareFuture<>(context());
    }

    @Override
    public CompletionStage<T> minimalCompletionStage() {
        return new Java12ContextAwareMinimalStage<>(this);
    }

    @Override
    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
        return super.completeAsync(makeContextAwareLoggingException(supplier));
    }

    @Override
    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier, Executor executor) {
        requireNonNull(executor, "executor");
        return super.completeAsync(makeContextAwareLoggingException(supplier), executor);
    }
}
