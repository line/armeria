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
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

final class DefaultAsyncLoader<T> implements AsyncLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAsyncLoader.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<
            DefaultAsyncLoader, CompletableFuture> loadFutureUpdater = AtomicReferenceFieldUpdater
            .newUpdater(DefaultAsyncLoader.class, CompletableFuture.class, "loadFuture");

    private final Function<@Nullable T, CompletableFuture<T>> loader;
    @Nullable
    private final Duration expireAfterLoad;
    @Nullable
    private final Predicate<@Nullable T> expireIf;
    @Nullable
    private final BiFunction<Throwable, @Nullable T, @Nullable CompletableFuture<T>> exceptionHandler;

    private volatile CompletableFuture<CacheEntry<T>> loadFuture = UnmodifiableFuture.completedFuture(null);

    DefaultAsyncLoader(Function<@Nullable T, CompletableFuture<T>> loader,
                       @Nullable Duration expireAfterLoad,
                       @Nullable Predicate<@Nullable T> expireIf,
                       @Nullable BiFunction<
                               Throwable, @Nullable T, @Nullable CompletableFuture<T>> exceptionHandler) {
        requireNonNull(loader, "loader");
        this.loader = loader;
        this.expireAfterLoad = expireAfterLoad;
        this.expireIf = expireIf;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public CompletableFuture<T> get() {
        return get0().thenApply(f -> f.loadVal);
    }

    private CompletableFuture<CacheEntry<T>> get0() {
        CompletableFuture<CacheEntry<T>> future;
        CacheEntry<T> cacheEntry = null;
        for (;;) {
            final CompletableFuture<CacheEntry<T>> loadFuture = this.loadFuture;
            if (!loadFuture.isDone()) {
                return loadFuture;
            }

            if (!loadFuture.isCompletedExceptionally()) {
                cacheEntry = loadFuture.join();
                if (isValid(cacheEntry)) {
                    return loadFuture;
                }
            }

            future = new CompletableFuture<>();
            if (loadFutureUpdater.compareAndSet(this, loadFuture, future)) {
                break;
            }
        }

        final T cache = cacheEntry != null ? cacheEntry.loadVal : null;
        final CompletableFuture<CacheEntry<T>> newLoadfuture = future;
        try {
            requireNonNull(loader.apply(cache), "loader.apply() returned null")
                    .handle((val, cause) -> {
                        if (cause != null) {
                            logger.warn("Failed to load a new value from loader: {}. the previous value: {}",
                                        loader, cache, cause);
                            handleException(cause, cache, newLoadfuture);
                        } else {
                            newLoadfuture.complete(new CacheEntry<>(val));
                        }
                        return null;
                    });
        } catch (Exception e) {
            logger.warn("Unexpected exception from loader.apply()", e);
            handleException(e, cache, newLoadfuture);
        }

        return newLoadfuture;
    }

    private void handleException(Throwable cause, @Nullable T cache,
                                 CompletableFuture<CacheEntry<T>> future) {
        if (exceptionHandler != null) {
            handleByExceptionHandler(cause, cache, future);
        } else {
            future.completeExceptionally(cause);
        }
    }

    private void handleByExceptionHandler(Throwable originCause, @Nullable T cache,
                                          CompletableFuture<CacheEntry<T>> future) {
        try {
            final CompletableFuture<T> handleException = exceptionHandler.apply(originCause, cache);
            if (handleException != null) {
                handleException.handle((val, cause) -> {
                    if (cause != null) {
                        logger.warn("Failed to load a new value from exceptionHandler: {}." +
                                    "the previous value: {}", exceptionHandler, cache, cause);
                        future.completeExceptionally(cause);
                    } else {
                        future.complete(new CacheEntry<>(val));
                    }
                    return null;
                });
            } else {
                future.completeExceptionally(originCause);
            }
        } catch (Exception e) {
            logger.warn("Unexpected exception from exceptionHandler.apply()", e);
            future.completeExceptionally(originCause);
        }
    }

    private boolean isValid(@Nullable CacheEntry<T> cacheEntry) {
        if (cacheEntry == null) {
            return false;
        }

        if (expireAfterLoad != null) {
            final Instant expiration = cacheEntry.loadWhen.plusMillis(expireAfterLoad.toMillis());
            if (Instant.now().isAfter(expiration)) {
                return false;
            }
        }

        if (expireIf != null && expireIf.test(cacheEntry.loadVal)) {
            return false;
        }

        return true;
    }

    private static class CacheEntry<T> {

        private final T loadVal;
        private final Instant loadWhen = Instant.now();

        CacheEntry(T loadVal) {
            requireNonNull(loadVal, "loadVal");
            this.loadVal = loadVal;
        }
    }
}
