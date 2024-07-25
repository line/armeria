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

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

final class DefaultAsyncLoader<T> implements AsyncLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAsyncLoader.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<
            DefaultAsyncLoader, CompletableFuture> loadFutureUpdater = AtomicReferenceFieldUpdater
            .newUpdater(DefaultAsyncLoader.class, CompletableFuture.class, "loadingFuture");

    private final Function<@Nullable T, CompletableFuture<T>> loader;
    private final long expireAfterLoadNanos;
    @Nullable
    private final Predicate<? super T> expireIf;
    @Nullable
    private final Predicate<? super T> refreshIf;
    @Nullable
    private final BiFunction<? super Throwable, ? super @Nullable T,
            ? extends @Nullable CompletableFuture<T>> exceptionHandler;

    @Nullable
    private volatile CacheEntry<T> cacheEntry;
    private volatile CompletableFuture<T> loadingFuture = UnmodifiableFuture.completedFuture(null);

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
    public CompletableFuture<T> load() {
        final CompletableFuture<T> loadingFuture = this.loadingFuture;
        if (!loadingFuture.isDone()) {
            // A load is in progress.
            return cacheOrFuture(cacheEntry, loadingFuture);
        }

        // A new `cacheEntry` is set before `loadingFuture` is completed.
        final CacheEntry<T> cacheEntry = this.cacheEntry;
        final boolean isValid = isValid(cacheEntry);
        final boolean tryLoad;
        if (isValid) {
            assert cacheEntry != null;
            tryLoad = needsRefresh(cacheEntry);
        } else {
            tryLoad = true;
        }

        if (tryLoad) {
            final CompletableFuture<T> newFuture = new CompletableFuture<>();
            if (loadFutureUpdater.compareAndSet(this, loadingFuture, newFuture)) {
                return logAndLoad(cacheEntry, isValid, newFuture);
            } else {
                // Re-read the cacheEntry and loadingFuture because they may be updated by another thread.
                return cacheOrFuture(this.cacheEntry, this.loadingFuture);
            }
        } else {
            // The cache is still valid and no need to refresh.
            return cacheOrFuture(cacheEntry, loadingFuture);
        }
    }

    private void load(@Nullable T cache, CompletableFuture<T> future) {
        try {
            loader.apply(cache).handle((newValue, cause) -> {
                if (cause != null) {
                    logger.warn("Failed to load a new value from loader: {}. cache: {}",
                                loader, cache, cause);
                    handleException(cause, cache, future);
                } else {
                    logger.debug("Loaded a new value: {}", newValue);
                    cacheEntry = new CacheEntry<>(newValue);
                    future.complete(newValue);
                }
                return null;
            });
        } catch (Exception e) {
            logger.warn("Unexpected exception from loader.apply()", e);
            handleException(e, cache, future);
        }
    }

    @Nonnull
    private CompletableFuture<T> logAndLoad(@Nullable CacheEntry<T> cacheEntry, boolean isValid,
                                            CompletableFuture<T> newFuture) {
        // cacheEntry is not null if isValid == true
        assert !isValid || cacheEntry != null;
        final T cache = cacheEntry != null ? cacheEntry.value : null;
        if (isValid) {
            logger.debug("Pre-fetching a new value. cache: {}, loader: {}", cache, loader);
        } else {
            logger.debug("Loading a new value. cache: {}, loader: {}", cache, loader);
        }

        load(cache, newFuture);

        if (isValid) {
            return UnmodifiableFuture.completedFuture(cache);
        } else {
            return newFuture;
        }
    }

    private CompletableFuture<T> cacheOrFuture(@Nullable CacheEntry<T> cacheEntry,
                                               CompletableFuture<T> future) {
        if (isValid(cacheEntry)) {
            assert cacheEntry != null;
            return UnmodifiableFuture.completedFuture(cacheEntry.value);
        }
        return future;
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
                                 CompletableFuture<T> future) {
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

            fallback.handle((newValue, cause0) -> {
                if (cause0 != null) {
                    logger.warn("Failed to load a new value from exceptionHandler: {}. " +
                                "cache: {}", exceptionHandler, cache, cause0);
                    future.completeExceptionally(cause0);
                } else {
                    cacheEntry = new CacheEntry<>(newValue);
                    future.complete(newValue);
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
            // The initial value is not loaded yet.
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

    private static final class CacheEntry<T> {

        @Nullable
        private final T value;
        private final long cachedAtNanos = System.nanoTime();

        CacheEntry(@Nullable T value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("value", value)
                              .add("cachedAtNanos", cachedAtNanos)
                              .toString();
        }
    }
}
