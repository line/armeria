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

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A loader which atomically loads, caches and updates value.
 */
@FunctionalInterface
public interface AsyncLoader<T> {

    /**
     * Returns a newly created {@link AsyncLoaderBuilder} with the specified loader.
     * @param loader function to load value. {@code T} is previously cached value
     *               or {@code null} when nothing is cached.
     */
    static <T> AsyncLoaderBuilder<T> builder(
            Function<@Nullable ? super T, ? extends CompletableFuture<T>> loader) {
        //noinspection unchecked
        return new AsyncLoaderBuilder<>((Function<T, CompletableFuture<T>>) loader);
    }

    /**
     * Returns a {@link CompletableFuture} which emits loaded value.
     * Loads new value by loader only if nothing is cached or loaded value is expired.
     */
    CompletableFuture<T> get();
}
