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
import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A builder for creating a new {@link AsyncLoader}.
 */
public final class AsyncLoaderBuilder<T> {

    private final Function<@Nullable T, CompletableFuture<T>> loader;
    @Nullable
    private Duration expireAfterLoad;
    @Nullable
    private Predicate<@Nullable T> expireIf;

    AsyncLoaderBuilder(Function<@Nullable T, CompletableFuture<T>> loader) {
        requireNonNull(loader, "loader");
        this.loader = loader;
    }

    /**
     * Expires loaded value after duration since it was loaded.
     * New value will be loaded by loader on next {@link AsyncLoader#get()}.
     */
    public AsyncLoaderBuilder<T> expireAfterLoad(Duration duration) {
        requireNonNull(duration, "duration");
        this.expireAfterLoad = duration;
        return this;
    }

    /**
     * Expires loaded value if predicate matches.
     * New value will be loaded by loader on next {@link AsyncLoader#get()}.
     */
    public AsyncLoaderBuilder<T> expireIf(Predicate<@Nullable T> predicate) {
        requireNonNull(predicate, "predicate");
        this.expireIf = predicate;
        return this;
    }

    /**
     * Returns a newly created {@link AsyncLoader} with the entries in this builder.
     */
    public AsyncLoader<T> build() {
        return new DefaultAsyncLoader<>(loader, expireAfterLoad, expireIf);
    }
}
