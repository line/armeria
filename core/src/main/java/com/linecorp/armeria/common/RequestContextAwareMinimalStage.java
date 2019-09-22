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

import com.google.common.base.MoreObjects;

@SuppressWarnings("unchecked")
final class RequestContextAwareMinimalStage<T> implements CompletionStage<T> {

    private final RequestContextAwareCompletableFuture<T> delegate;

    RequestContextAwareMinimalStage(RequestContextAwareCompletableFuture<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public <U> CompletionStage<U> thenApply(Function<? super T, ? extends U> fn) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<U>) delegate.thenApply(fn));
    }

    @Override
    public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<U>) delegate.thenApplyAsync(fn));
    }

    @Override
    public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<U>) delegate.thenApplyAsync(fn, executor));
    }

    @Override
    public CompletionStage<Void> thenAccept(Consumer<? super T> action) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.thenAccept(action));
    }

    @Override
    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.thenAcceptAsync(action));
    }

    @Override
    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.thenAcceptAsync(action, executor));
    }

    @Override
    public CompletionStage<Void> thenRun(Runnable action) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.thenRun(action));
    }

    @Override
    public CompletionStage<Void> thenRunAsync(Runnable action) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.thenRunAsync(action));
    }

    @Override
    public CompletionStage<Void> thenRunAsync(Runnable action, Executor executor) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.thenRunAsync(action, executor));
    }

    @Override
    public <U, V> CompletionStage<V> thenCombine(CompletionStage<? extends U> other,
                                                 BiFunction<? super T, ? super U, ? extends V> fn) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<V>) delegate.thenCombine(other, fn));
    }

    @Override
    public <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other,
                                                      BiFunction<? super T, ? super U, ? extends V> fn) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<V>) delegate.thenCombineAsync(other, fn));
    }

    @Override
    public <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other,
                                                      BiFunction<? super T, ? super U, ? extends V> fn,
                                                      Executor executor) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<V>) delegate.thenCombineAsync(other, fn, executor));
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBoth(CompletionStage<? extends U> other,
                                                    BiConsumer<? super T, ? super U> action) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.thenAcceptBoth(other, action));
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
                                                         BiConsumer<? super T, ? super U> action) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.thenAcceptBothAsync(other, action));
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
                                                         BiConsumer<? super T, ? super U> action,
                                                         Executor executor) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.thenAcceptBothAsync(other, action,
                                                                                          executor));
    }

    @Override
    public CompletionStage<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.runAfterBoth(other, action));
    }

    @Override
    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.runAfterBothAsync(other, action));
    }

    @Override
    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action,
                                                   Executor executor) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.runAfterBothAsync(other, action,
                                                                                        executor));
    }

    @Override
    public <U> CompletionStage<U> applyToEither(CompletionStage<? extends T> other,
                                                Function<? super T, U> fn) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<U>) delegate.applyToEither(other, fn));
    }

    @Override
    public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other,
                                                     Function<? super T, U> fn) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<U>) delegate.applyToEitherAsync(other, fn));
    }

    @Override
    public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other,
                                                     Function<? super T, U> fn,
                                                     Executor executor) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<U>) delegate.applyToEitherAsync(other, fn, executor));
    }

    @Override
    public CompletionStage<Void> acceptEither(CompletionStage<? extends T> other,
                                              Consumer<? super T> action) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.acceptEither(other, action));
    }

    @Override
    public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other,
                                                   Consumer<? super T> action) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.acceptEitherAsync(other, action));
    }

    @Override
    public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other,
                                                   Consumer<? super T> action, Executor executor) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.acceptEitherAsync(other, action,
                                                                                        executor));
    }

    @Override
    public CompletionStage<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.runAfterEither(other, action));
    }

    @Override
    public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.runAfterEitherAsync(other, action));
    }

    @Override
    public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other,
                                                     Runnable action,
                                                     Executor executor) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<Void>) delegate.runAfterEitherAsync(other, action,
                                                                                          executor));
    }

    @Override
    public <U> CompletionStage<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<U>) delegate.thenCompose(fn));
    }

    @Override
    public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<U>) delegate.thenComposeAsync(fn));
    }

    @Override
    public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn,
                                                   Executor executor) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<U>) delegate.thenComposeAsync(fn, executor));
    }

    @Override
    public <U> CompletionStage<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<U>) delegate.handle(fn));
    }

    @Override
    public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<U>) delegate.handleAsync(fn));
    }

    @Override
    public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn,
                                              Executor executor) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<U>) delegate.handleAsync(fn, executor));
    }

    @Override
    public CompletionStage<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<T>) delegate.whenComplete(action));
    }

    @Override
    public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<T>) delegate.whenCompleteAsync(action));
    }

    @Override
    public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action,
                                                Executor executor) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<T>) delegate.whenCompleteAsync(action, executor));
    }

    @Override
    public CompletionStage<T> exceptionally(Function<Throwable, ? extends T> fn) {
        return new RequestContextAwareMinimalStage(
                (RequestContextAwareCompletableFuture<T>) delegate.exceptionally(fn));
    }

    @Override
    public CompletableFuture<T> toCompletableFuture() {
        return delegate.thenApply(Function.identity());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("delegate", delegate)
                .toString();
    }
}
