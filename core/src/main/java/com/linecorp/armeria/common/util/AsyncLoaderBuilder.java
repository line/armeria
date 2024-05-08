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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A builder for creating a new {@link AsyncLoader}.
 *
 * <p>Expiration should be set by {@link #expireAfterLoad(Duration)} or {@link #expireIf(Predicate)}.
 * If expiration is not set, {@link #build()} will throw {@link IllegalStateException}.
 */
public final class AsyncLoaderBuilder<T> {

    private final Function<@Nullable T, CompletableFuture<T>> loader;
    @Nullable
    private Duration expireAfterLoad;
    @Nullable
    private Predicate<@Nullable T> expireIf;
    @Nullable
    private Predicate<@Nullable T> refreshIf;
    @Nullable
    private BiFunction<Throwable, @Nullable T, @Nullable CompletableFuture<T>> exceptionHandler;

    AsyncLoaderBuilder(Function<@Nullable T, CompletableFuture<T>> loader) {
        requireNonNull(loader, "loader");
        this.loader = loader;
    }

    /**
     * Expires loaded value after duration since it was loaded.
     * New value will be loaded by loader on next {@link AsyncLoader#get()}.
     */
    public AsyncLoaderBuilder<T> expireAfterLoad(Duration expireAfterLoad) {
        requireNonNull(expireAfterLoad, "expireAfterLoad");
        this.expireAfterLoad = expireAfterLoad;
        return this;
    }

    /**
     * Expires loaded value if predicate matches.
     * New value will be loaded by loader on next {@link AsyncLoader#get()}.
     */
    public AsyncLoaderBuilder<T> expireIf(Predicate<@Nullable T> expireIf) {
        requireNonNull(expireIf, "expireIf");
        this.expireIf = expireIf;
        return this;
    }

    /**
     * Refreshes loaded value which is not expired yet asynchronously if predicate matches.
     * This pre-fetch strategy can remove an additional loading time on a cache miss.
     *
     * <p>Note that if 1 refresh is in progress, other refreshes will be bypassed.
     * Only 1 refresh is executed at the same time.
     */
    public AsyncLoaderBuilder<T> refreshIf(Predicate<@Nullable T> refreshIf) {
        requireNonNull(refreshIf, "refreshIf");
        this.refreshIf = refreshIf;
        return this;
    }

    /**
     * Handles exception thrown by loader.
     * If exception handler returns {@code null}, complete {@link AsyncLoader#get()} exceptionally.
     */
    public AsyncLoaderBuilder<T> exceptionHandler(BiFunction<
            Throwable, @Nullable T, @Nullable CompletableFuture<T>> exceptionHandler) {
        requireNonNull(exceptionHandler, "exceptionHandler");
        this.exceptionHandler = exceptionHandler;
        return this;
    }

    /**
     * Returns a newly created {@link AsyncLoader} with the entries in this builder.
     *
     * @throws IllegalStateException if expiration is not set.
     */
    public AsyncLoader<T> build() {
        if (expireAfterLoad == null && expireIf == null) {
            throw new IllegalStateException("Must set AsyncLoader's expiration.");
        }
        return new DefaultAsyncLoader<>(loader, expireAfterLoad, expireIf, refreshIf, exceptionHandler);
    }
}
