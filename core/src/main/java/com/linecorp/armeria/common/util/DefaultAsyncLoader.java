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
            DefaultAsyncLoader, RefreshingFuture> loadFutureUpdater = AtomicReferenceFieldUpdater
            .newUpdater(DefaultAsyncLoader.class, RefreshingFuture.class, "loadFuture");

    private final Function<@Nullable T, CompletableFuture<T>> loader;
    @Nullable
    private final Duration expireAfterLoad;
    @Nullable
    private final Predicate<@Nullable T> expireIf;
    @Nullable
    private final Predicate<@Nullable T> refreshIf;
    @Nullable
    private final BiFunction<Throwable, @Nullable T, @Nullable CompletableFuture<T>> exceptionHandler;

    private volatile RefreshingFuture<T> loadFuture = RefreshingFuture.completedFuture();

    DefaultAsyncLoader(Function<@Nullable T, CompletableFuture<T>> loader,
                       @Nullable Duration expireAfterLoad,
                       @Nullable Predicate<@Nullable T> expireIf,
                       @Nullable Predicate<@Nullable T> refreshIf,
                       @Nullable BiFunction<
                               Throwable, @Nullable T, @Nullable CompletableFuture<T>> exceptionHandler) {
        requireNonNull(loader, "loader");
        this.loader = loader;
        this.expireAfterLoad = expireAfterLoad;
        this.expireIf = expireIf;
        this.refreshIf = refreshIf;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public CompletableFuture<T> get() {
        return maybeLoad().thenApply(f -> f.value);
    }

    private CompletableFuture<CacheEntry<T>> maybeLoad() {
        RefreshingFuture<T> future;
        CacheEntry<T> cacheEntry = null;
        for (;;) {
            final RefreshingFuture<T> loadFuture = this.loadFuture;
            if (!loadFuture.isDone()) {
                return loadFuture;
            }

            if (!loadFuture.isCompletedExceptionally()) {
                final CacheEntry<T> cacheEntry0 = loadFuture.join();
                if (isValid(cacheEntry0)) {
                    cacheEntry = cacheEntry0;
                    final boolean needsRefresh = needsRefresh(cacheEntry);
                    if (!needsRefresh || loadFuture.refreshing) {
                        if (needsRefresh) {
                            logger.debug("Refreshing already in progress, " +
                                    "so not refresh it again. cacheEntry {}", cacheEntry);
                        }
                        return loadFuture;
                    }
                    logger.debug("Refresh cacheEntry {}", cacheEntry);
                }
            }

            future = new RefreshingFuture<>(cacheEntry);
            if (loadFutureUpdater.compareAndSet(this, loadFuture, future)) {
                break;
            }
        }

        final T cache = cacheEntry != null ? cacheEntry.value : null;
        load(cache, future);

        return future;
    }

    @Nullable
    private boolean needsRefresh(@Nullable CacheEntry<T> cacheEntry) {
        if (cacheEntry == null) {
            return false;
        }

        boolean refresh = false;
        final T cache = cacheEntry.value;
        try {
            refresh = refreshIf != null && refreshIf.test(cache);
        } catch (Exception e) {
            logger.warn("Unexpected exception from refreshIf.test()", e);
        }

        return refresh;
    }

    private void load(@Nullable T cache, CompletableFuture<CacheEntry<T>> future) {
        try {
            requireNonNull(loader.apply(cache), "loader.apply() returned null")
                    .handle((val, cause) -> {
                        if (cause != null) {
                            logger.warn("Failed to load a new value from loader: {}. the previous value: {}",
                                        loader, cache, cause);
                            handleException(cause, cache, future);
                        } else {
                            future.complete(new CacheEntry<>(val));
                        }
                        return null;
                    });
        } catch (Exception e) {
            logger.warn("Unexpected exception from loader.apply()", e);
            handleException(e, cache, future);
        }
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
            final CompletableFuture<T> fallback = exceptionHandler.apply(originCause, cache);
            if (fallback != null) {
                fallback.handle((val, cause) -> {
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
            final long elapsed = System.nanoTime() - cacheEntry.cachedAt;
            if (elapsed >= expireAfterLoad.toNanos()) {
                logger.debug("CacheEntry {} is expired due to expireAfterLoad {}",
                        cacheEntry, expireAfterLoad);
                return false;
            }
        }

        if (expireIf != null && expireIf.test(cacheEntry.value)) {
            logger.debug("CacheEntry {} is expired due to expireIf matches", cacheEntry);
            return false;
        }

        return true;
    }

    private static class CacheEntry<T> {

        private final T value;
        private final long cachedAt = System.nanoTime();

        CacheEntry(T value) {
            requireNonNull(value, "value");
            this.value = value;
        }
    }

    private static class RefreshingFuture<U> extends CompletableFuture<CacheEntry<U>> {

        private static final RefreshingFuture<?> COMPLETED;

        static {
            COMPLETED = new RefreshingFuture<>(null);
            COMPLETED.complete(null);
        }

        @SuppressWarnings("unchecked")
        static <T> RefreshingFuture<T> completedFuture() {
            return (RefreshingFuture<T>) COMPLETED;
        }

        @Nullable
        private volatile CacheEntry<U> cacheEntry;
        // True when refreshing is in progress, means not yet completed by new value or exception.
        private volatile boolean refreshing;

        RefreshingFuture(@Nullable CacheEntry<U> cacheEntry) {
            // cacheEntry can be null if it expired or has not loaded yet.
            this.cacheEntry = cacheEntry;
            if (cacheEntry != null) {
                complete(cacheEntry);
            }
            refreshing = true;
        }

        @Override
        public boolean complete(CacheEntry<U> newVal) {
            refreshing = false;
            cacheEntry = newVal;
            obtrudeValue(newVal);
            return true;
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            refreshing = false;
            if (cacheEntry != null) {
                logger.warn("Failed to refresh a new value. the previous value: {}", cacheEntry.value, ex);
            } else {
                obtrudeException(ex);
            }
            return true;
        }

        @Override
        public CacheEntry<U> join() {
            final CacheEntry<U> cacheEntry = this.cacheEntry;
            if (cacheEntry != null) {
                return cacheEntry;
            }
            return super.join();
        }
    }
}
