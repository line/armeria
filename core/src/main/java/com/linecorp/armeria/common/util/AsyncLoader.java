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
 *
 * <p>Example usage:
 * <pre>{@code
 * WebClient client = WebClient.of("https://example.com");
 * Function<String, CompletableFuture<String>> loader = (cache) -> {
 *     // Fetch new data from the remote server.
 *     ResponseEntity<String> response =
 *         client.prepare().get("/bar").asString().execute();
 *     return response.thenApply(res -> res.content());
 * };
 *
 * AsyncLoader<String> asyncLoader = AsyncLoader
 *     .builder(loader)
 *     .expireAfterLoad(Duration.ofSeconds(60))  // The loaded value is expired after 60 seconds.
 *     .build();
 *
 * // Fetch the value. This will call the loader function because the cache is empty.
 * String value1 = asyncLoader.get().join();
 * System.out.println("Loaded value: " + value1);
 *
 * // Fetch the value again. This will return the cached value because it's not expired yet.
 * String value2 = asyncLoader.get().join();
 * assert value1 == value2;
 *
 * // Wait for more than 60 seconds so that the cache is expired.
 * Thread.sleep(61000);
 *
 * // Fetch the value again. This will call the loader function because the cache has expired.
 * String value3 = asyncLoader.get().join();
 * assert value1 != value3;
 * }</pre>
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
     * Returns a {@link CompletableFuture} which will be completed with the loaded value.
     * A new value is fetched by the loader only if nothing is cached or the cache value has expired.
     */
    CompletableFuture<T> get();
}
