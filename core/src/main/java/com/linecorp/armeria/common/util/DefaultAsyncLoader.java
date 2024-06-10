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
import java.util.concurrent.TimeUnit;
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
    private final long expireAfterLoadNanos;
    @Nullable
    private final Predicate<? super T> expireIf;
    @Nullable
    private final Predicate<? super T> refreshIf;
    @Nullable
    private final BiFunction<? super Throwable, ? super @Nullable T,
            ? extends @Nullable CompletableFuture<T>> exceptionHandler;

    private volatile RefreshingFuture<T> loadFuture = RefreshingFuture.completedFuture();

    DefaultAsyncLoader(Function<@Nullable T, CompletableFuture<T>> loader,
                       @Nullable Duration expireAfterLoad,
                       @Nullable Predicate<? super T> expireIf,
                       @Nullable Predicate<? super T> refreshIf,
                       @Nullable BiFunction<? super Throwable, ? super @Nullable T,
                               ? extends @Nullable CompletableFuture<T>> exceptionHandler) {
        requireNonNull(loader, "loader");
        this.loader = loader;
        expireAfterLoadNanos = expireAfterLoad != null ? expireAfterLoad.toNanos() : 0;
        this.expireIf = expireIf;
        this.refreshIf = refreshIf;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public CompletableFuture<T> get() {
        return maybeLoad().thenApply(cacheEntry -> cacheEntry.value);
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
                    if (!needsRefresh(cacheEntry) || loadFuture.refreshing) {
                        return loadFuture;
                    }
                    logger.debug("Pre-fetching a new value. loader: {}, cache: {}", loader, cacheEntry.value);
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

    private void load(@Nullable T cache, CompletableFuture<CacheEntry<T>> future) {
        try {
            requireNonNull(loader.apply(cache), "loader.apply() returned null")
                    .handle((value, cause) -> {
                        if (cause != null) {
                            logger.warn("Failed to load a new value from loader: {}. cache: {}",
                                        loader, cache, cause);
                            handleException(cause, cache, future);
                        } else {
                            future.complete(new CacheEntry<>(value));
                        }
                        return null;
                    });
        } catch (Exception e) {
            logger.warn("Unexpected exception from loader.apply()", e);
            handleException(e, cache, future);
        }
    }

    private boolean needsRefresh(CacheEntry<T> cacheEntry) {
        boolean refresh = false;
        final T cache = cacheEntry.value;
        try {
            refresh = refreshIf != null && refreshIf.test(cache);
        } catch (Exception e) {
            logger.warn("Unexpected exception from refreshIf.test()", e);
        }

        return refresh;
    }

    private void handleException(Throwable cause, @Nullable T cache,
                                 CompletableFuture<CacheEntry<T>> future) {
        if (exceptionHandler == null) {
            future.completeExceptionally(cause);
            return;
        }

        try {
            final CompletableFuture<T> fallback = exceptionHandler.apply(cause, cache);
            if (fallback == null) {
                future.completeExceptionally(cause);
                return;
            }

            fallback.handle((value, cause0) -> {
                if (cause0 != null) {
                    logger.warn("Failed to load a new value from exceptionHandler: {}. " +
                                "cache: {}", exceptionHandler, cache, cause0);
                    future.completeExceptionally(cause0);
                } else {
                    future.complete(new CacheEntry<>(value));
                }
                return null;
            });
        } catch (Exception e) {
            logger.warn("Unexpected exception from exceptionHandler.apply()", e);
            future.completeExceptionally(cause);
        }
    }

    private boolean isValid(@Nullable CacheEntry<T> cacheEntry) {
        if (cacheEntry == null) {
            return false;
        }

        if (expireAfterLoadNanos > 0) {
            final long elapsed = System.nanoTime() - cacheEntry.cachedAtNanos;
            if (elapsed >= expireAfterLoadNanos) {
                if (logger.isDebugEnabled()) {
                    logger.debug("The cached value expired after {} ms. cache: {}",
                                 TimeUnit.NANOSECONDS.toMillis(expireAfterLoadNanos), cacheEntry.value);
                }
                return false;
            }
        }

        try {
            if (expireIf != null && expireIf.test(cacheEntry.value)) {
                logger.debug("The cached value expired due to 'expireIf' condition. cache: {}",
                             cacheEntry.value);
                return false;
            }
        } catch (Exception e) {
            logger.warn("Unexpected exception from expireIf.test()", e);
        }

        return true;
    }

    private static class CacheEntry<T> {

        private final T value;
        private final long cachedAtNanos = System.nanoTime();

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
            final CacheEntry<U> cacheEntry = this.cacheEntry;
            if (cacheEntry != null) {
                logger.warn("Failed to refresh a new value. cache: {}", cacheEntry.value, ex);
            } else {
                obtrudeException(ex);
            }
            return true;
        }
    }
}
