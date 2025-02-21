/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.util.IdentityHashStrategy;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenCustomHashSet;

/**
 * A helper class which allows users to easily implement asynchronous selection of elements of type {@link T}.
 * Users are expected to implement the synchronous method {@link #selectNow(ClientRequestContext)}.
 * When {@link #selectNow(ClientRequestContext)} is expected to have changed its value, {@link #refresh()}
 * must be called to notify pending futures.
 * <pre>{@code
 * class MySelector extends AbstractAsyncSelector<String> {
 *     private String value;
 *     @Override
 *     protected String selectNow(ClientRequestContext ctx) {
 *         return value;
 *     }
 * }
 *
 * MySelector mySelector = new MySelector();
 * val selected1 = mySelector.selectNow(); // null
 *
 * mySelector.value = "hello";
 * val selected2 = mySelector.selectNow(); // null
 *
 * mySelector.refresh(); // pending futures will now try to select again
 * val selected3 = mySelector.selectNow(); // hello
 * }</pre>
 */
@UnstableApi
public abstract class AbstractAsyncSelector<T> {

    private final ReentrantShortLock lock = new ReentrantShortLock();
    @GuardedBy("lock")
    private final Set<ListeningFuture> pendingFutures =
            new ObjectLinkedOpenCustomHashSet<>(IdentityHashStrategy.of());

    /**
     * Creates a new instance.
     */
    protected AbstractAsyncSelector() {
    }

    @SuppressWarnings("GuardedBy")
    @VisibleForTesting
    final Set<ListeningFuture> pendingFutures() {
        return pendingFutures;
    }

    /**
     * Selects an element based on the provided {@link ClientRequestContext}.
     * Users may return {@code null} to indicate that it isn't possible to select a value now.
     */
    @Nullable
    protected abstract T selectNow(ClientRequestContext ctx);

    /**
     * Select an element asynchronously. If an element is readily available, this method will return
     * immediately. Otherwise, a future will be queued and wait for the next {@link #refresh()} invocation.
     * If the specified timeout is passed, the future will complete with {@code null}.
     *
     * @param executor the executor which will schedule a timeout
     * @param selectionTimeoutMillis the amount of time to wait before completing a pending future
     */
    public CompletableFuture<T> select(ClientRequestContext ctx, ScheduledExecutorService executor,
                                       long selectionTimeoutMillis) {
        checkArgument(selectionTimeoutMillis >= 0, "selectionTimeoutMillis: %s (expected: >= 0)",
                      selectionTimeoutMillis);
        T selected = selectNow(ctx);
        if (selected != null) {
            return UnmodifiableFuture.completedFuture(selected);
        }

        final ListeningFuture listeningFuture = new ListeningFuture(ctx, executor);
        addPendingFuture(listeningFuture);

        // The EndpointGroup have just been updated after adding ListeningFuture.
        if (listeningFuture.isDone()) {
            return listeningFuture;
        }

        selected = selectNow(ctx);
        if (selected != null) {
            // The state has just been updated before adding ListeningFuture.
            listeningFuture.complete(selected);
            return listeningFuture;
        }

        if (selectionTimeoutMillis == 0) {
            return UnmodifiableFuture.completedFuture(null);
        }

        // Schedule the timeout task.
        if (selectionTimeoutMillis < Long.MAX_VALUE) {
            final ScheduledFuture<?> timeoutFuture = executor.schedule(() -> {
                onTimeout(ctx, selectionTimeoutMillis);
                // Don't complete exceptionally so that the throwable
                // can be handled after executing the attached decorators
                listeningFuture.complete(null);
            }, selectionTimeoutMillis, TimeUnit.MILLISECONDS);
            listeningFuture.timeoutFuture = timeoutFuture;

            // Cancel the timeout task if listeningFuture is done already.
            // This guards against the following race condition:
            // 1) (Current thread) Timeout task is scheduled.
            // 2) ( Other thread ) listeningFuture is completed, but the timeout task is not cancelled
            // 3) (Current thread) timeoutFuture is assigned to listeningFuture.timeoutFuture, but it's too
            // late.
            if (listeningFuture.isDone()) {
                timeoutFuture.cancel(false);
            }
        }

        return listeningFuture;
    }

    /**
     * A hook which is executed when a timeout has been triggered.
     */
    @UnstableApi
    protected void onTimeout(ClientRequestContext ctx, long selectionTimeoutMillis) {
    }

    /**
     * Triggers pending futures to try and select a value by calling {@link #selectNow(ClientRequestContext)}.
     */
    public void refresh() {
        lock.lock();
        try {
            pendingFutures.removeIf(ListeningFuture::tryComplete);
        } finally {
            lock.unlock();
        }
    }

    private void addPendingFuture(ListeningFuture future) {
        lock.lock();
        try {
            pendingFutures.add(future);
        } finally {
            lock.unlock();
        }
    }

    private void removePendingFuture(ListeningFuture future) {
        lock.lock();
        try {
            pendingFutures.remove(future);
        } finally {
            lock.unlock();
        }
    }

    @VisibleForTesting
    final class ListeningFuture extends CompletableFuture<T> {
        private final ClientRequestContext ctx;
        private final Executor executor;
        @Nullable
        private volatile T selected;
        @Nullable
        private volatile ScheduledFuture<?> timeoutFuture;

        ListeningFuture(ClientRequestContext ctx, Executor executor) {
            this.ctx = ctx;
            this.executor = executor;
        }

        private boolean tryComplete() {
            if (selected != null || isDone()) {
                return true;
            }

            try {
                final T selected = selectNow(ctx);
                if (selected == null) {
                    return false;
                }

                cleanup(false);
                // Complete with the selected endpoint.
                this.selected = selected;
                executor.execute(() -> super.complete(selected));
                return true;
            } catch (Throwable t) {
                cleanup(false);
                super.completeExceptionally(t);
                return true;
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cleanup(true);
            return super.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean complete(@Nullable T value) {
            cleanup(true);
            return super.complete(value);
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            cleanup(true);
            return super.completeExceptionally(ex);
        }

        private void cleanup(boolean removePendingFuture) {
            if (removePendingFuture) {
                removePendingFuture(this);
            }
            final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
            if (timeoutFuture != null) {
                this.timeoutFuture = null;
                timeoutFuture.cancel(false);
            }
        }

        @Nullable
        @VisibleForTesting
        ScheduledFuture<?> timeoutFuture() {
            return timeoutFuture;
        }
    }
}
