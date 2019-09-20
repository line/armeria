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

import com.linecorp.armeria.common.util.SafeCloseable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class RequestContextAwareCompletableFuture<T> extends CompletableFuture<T> {

    private final RequestContext ctx;

    RequestContextAwareCompletableFuture(RequestContext requestContext) {
        ctx = requestContext;
    }

    @Override
    public <U> CompletableFuture<U> thenApply(Function<? super T,? extends U> fn) {
        final CompletableFuture<U> result = super.thenApply(ctx.makeContextAware(fn));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T,? extends U> fn) {
        final CompletableFuture<U> result = super.thenApplyAsync(ctx.makeContextAware(fn));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T,? extends U> fn, Executor executor) {
        final CompletableFuture<U> result = super.thenApplyAsync(ctx.makeContextAware(fn), executor);
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> thenAccept(Consumer<? super T> action) {
        final CompletableFuture<Void> result = super.thenAccept(ctx.makeContextAware(action));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
        final CompletableFuture<Void> result = super.thenAcceptAsync(ctx.makeContextAware(action));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        final CompletableFuture<Void> result = super.thenAcceptAsync(ctx.makeContextAware(action), executor);
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> thenRun(Runnable action) {
        final CompletableFuture<Void> result = super.thenRun(ctx.makeContextAware(action));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> thenRunAsync(Runnable action) {
        final CompletableFuture<Void> result = super.thenRunAsync(ctx.makeContextAware(action));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> thenRunAsync(Runnable action, Executor executor) {
        final CompletableFuture<Void> result = super.thenRunAsync(ctx.makeContextAware(action), executor);
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U,V> CompletableFuture<V> thenCombine(CompletionStage<? extends U> other,
                                                  BiFunction<? super T,? super U,? extends V> fn) {
        final CompletableFuture<V> result = super.thenCombine(other, ctx.makeContextAware(fn));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U,V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
                                                       BiFunction<? super T,? super U,? extends V> fn) {
        final CompletableFuture<V> result = super.thenCombineAsync(other, ctx.makeContextAware(fn));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U,V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
                                                       BiFunction<? super T,? super U,? extends V> fn,
                                                       Executor executor) {
        final CompletableFuture<V> result = super.thenCombineAsync(other, ctx.makeContextAware(fn), executor);
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBoth(CompletionStage<? extends U> other,
                                                      BiConsumer<? super T, ? super U> action) {
        final CompletableFuture<Void> result = super.thenAcceptBoth(other, ctx.makeContextAware(action));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
                                                           BiConsumer<? super T, ? super U> action) {
        final CompletableFuture<Void> result = super.thenAcceptBothAsync(other, ctx.makeContextAware(action));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
                                                           BiConsumer<? super T, ? super U> action,
                                                           Executor executor) {
        final CompletableFuture<Void> result = super.thenAcceptBothAsync(
                other, ctx.makeContextAware(action), executor);
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> runAfterBoth(CompletionStage<?> other,
                                                Runnable action) {
        final CompletableFuture<Void> result = super.runAfterBoth(other, ctx.makeContextAware(action));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other,
                                                     Runnable action) {
        final CompletableFuture<Void> result = super.runAfterBothAsync(other, ctx.makeContextAware(action));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other,
                                                     Runnable action,
                                                     Executor executor) {
        final CompletableFuture<Void> result = super.runAfterBothAsync(
                other, ctx.makeContextAware(action), executor);
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U> CompletableFuture<U> applyToEither(CompletionStage<? extends T> other,
                                                  Function<? super T, U> fn) {
        final CompletableFuture<U> result = super.applyToEither(other, ctx.makeContextAware(fn));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other,
                                                       Function<? super T, U> fn) {
        final CompletableFuture<U> result = super.applyToEitherAsync(other, ctx.makeContextAware(fn));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other,
                                                       Function<? super T, U> fn,
                                                       Executor executor) {
        final CompletableFuture<U> result = super.applyToEitherAsync(other, ctx.makeContextAware(fn), executor);
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> acceptEither(CompletionStage<? extends T> other,
                                                Consumer<? super T> action) {
        final CompletableFuture<Void> result = super.acceptEither(other, ctx.makeContextAware(action));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other,
                                                     Consumer<? super T> action) {
        final CompletableFuture<Void> result = super.acceptEitherAsync(other, ctx.makeContextAware(action));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other,
                                                     Consumer<? super T> action,
                                                     Executor executor) {
        final CompletableFuture<Void> result = super.acceptEitherAsync(
                other, ctx.makeContextAware(action), executor);
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> runAfterEither(CompletionStage<?> other,
                                                  Runnable action) {
        final CompletableFuture<Void> result = super.runAfterEither(other, ctx.makeContextAware(action));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other,
                                                       Runnable action) {
        final CompletableFuture<Void> result = super.runAfterEitherAsync(other, ctx.makeContextAware(action));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other,
                                                       Runnable action,
                                                       Executor executor) {
        final CompletableFuture<Void> result = super.runAfterEitherAsync(
                other, ctx.makeContextAware(action), executor);
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
        final CompletableFuture<U> result = super.thenCompose(ctx.makeContextAware(fn));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {
        final CompletableFuture<U> result = super.thenComposeAsync(ctx.makeContextAware(fn));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn,
                                                     Executor executor) {
        final CompletableFuture<U> result = super.thenComposeAsync(ctx.makeContextAware(fn), executor);
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        final CompletableFuture<T> result = super.whenComplete(ctx.makeContextAware(action));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        final CompletableFuture<T> result = super.whenCompleteAsync(ctx.makeContextAware(action));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action,
                                                  Executor executor) {
        final CompletableFuture<T> result = super.whenCompleteAsync(ctx.makeContextAware(action),
                                                                    executor);
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
        final CompletableFuture<U> result = super.handle(ctx.makeContextAware(fn));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
        final CompletableFuture<U> result = super.handleAsync(ctx.makeContextAware(fn));
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn,
                                                Executor executor) {
        final CompletableFuture<U> result = super.handleAsync(ctx.makeContextAware(fn), executor);
        if (result instanceof RequestContextAwareCompletableFuture) {
            return result;
        }
        return ctx.makeContextAware(result);
    }

    @Override
    public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
        return ctx.makeContextAware(super.exceptionally(ctx.makeContextAware(fn)));
    }

    // support JDK9 functions

    public <U> CompletableFuture<U> newIncompleteFuture() {
        return new RequestContextAwareCompletableFuture<>(ctx);
    }

    public CompletionStage<T> minimalCompletionStage() {
        return new RequestContextAwareMinimalStage<>(this);
    }

    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
        supplyAsync(this.makeContextAware(supplier)).handle((result, cause) -> {
            if (cause != null) {
                completeExceptionally(cause);
            } else {
                complete(result);
            }
            return null;
        });
        return this;
    }

    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier,
                                              Executor executor) {
        supplyAsync(this.makeContextAware(supplier), executor).handle((result, cause) -> {
            if (cause != null) {
                completeExceptionally(cause);
            } else {
                complete(result);
            }
            return null;
        });
        return this;
    }

    private Supplier<T> makeContextAware(Supplier<? extends T> action) {
        return () -> {
            try (SafeCloseable ignored = ctx.pushIfAbsent()) {
                return action.get();
            }
        };
    }
}
