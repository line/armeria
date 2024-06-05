/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.common.util;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for creating a new {@link AsyncLoader}.
 *
 * <p>Expiration should be set by {@link #expireAfterLoad(Duration)} or {@link #expireIf(Predicate)}.
 * If expiration is not set, {@link #build()} will throw {@link IllegalStateException}.
 */
@UnstableApi
public final class AsyncLoaderBuilder<T> {

    private final Function<@Nullable T, CompletableFuture<T>> loader;
    @Nullable
    private Duration expireAfterLoad;
    @Nullable
    private Predicate<? super T> expireIf;
    @Nullable
    private Predicate<? super T> refreshIf;
    @Nullable
    private BiFunction<? super Throwable, ? super @Nullable T,
            ? extends @Nullable CompletableFuture<T>> exceptionHandler;

    AsyncLoaderBuilder(Function<@Nullable T, CompletableFuture<T>> loader) {
        requireNonNull(loader, "loader");
        this.loader = loader;
    }

    /**
     * Expires The loaded value after the duration since it was loaded.
     * New value will be loaded by the loader function on next {@link AsyncLoader#get()}.
     */
    public AsyncLoaderBuilder<T> expireAfterLoad(Duration expireAfterLoad) {
        requireNonNull(expireAfterLoad, "expireAfterLoad");
        checkState(!expireAfterLoad.isNegative(), "expireAfterLoad: %s (expected: >= 0)", expireAfterLoad);
        this.expireAfterLoad = expireAfterLoad;
        return this;
    }

    /**
     * Expires the loaded value if the predicate matches.
     * New value will be loaded by the loader function on next {@link AsyncLoader#get()}.
     */
    public AsyncLoaderBuilder<T> expireIf(Predicate<? super T> expireIf) {
        requireNonNull(expireIf, "expireIf");
        this.expireIf = expireIf;
        return this;
    }

    /**
     * Asynchronously refreshes the loaded value which has not yet expired if the predicate matches.
     * This pre-fetch strategy can remove an additional loading time on a cache miss.
     */
    public AsyncLoaderBuilder<T> refreshIf(Predicate<? super T> refreshIf) {
        requireNonNull(refreshIf, "refreshIf");
        this.refreshIf = refreshIf;
        return this;
    }

    /**
     * Handles the exception thrown by the loader function.
     * If the exception handler returns {@code null}, {@link AsyncLoader#get()} completes exceptionally.
     */
    public AsyncLoaderBuilder<T> exceptionHandler(BiFunction<? super Throwable, ? super @Nullable T,
            ? extends @Nullable CompletableFuture<T>> exceptionHandler) {
        requireNonNull(exceptionHandler, "exceptionHandler");
        this.exceptionHandler = exceptionHandler;
        return this;
    }

    /**
     * Returns a newly created {@link AsyncLoader} with the entries in this builder.
     *
     * @throws IllegalStateException if no expiration is set.
     */
    public AsyncLoader<T> build() {
        if (expireAfterLoad == null && expireIf == null) {
            throw new IllegalStateException("Must set at least one of 'expireAfterLoad()` and 'expireIf()'.");
        }
        return new DefaultAsyncLoader<>(loader, expireAfterLoad, expireIf, refreshIf, exceptionHandler);
    }
}
