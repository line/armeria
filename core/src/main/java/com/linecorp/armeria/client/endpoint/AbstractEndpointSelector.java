/*
 * Copyright 2020 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.common.util.IdentityHashStrategy;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenCustomHashSet;

/**
 * A skeletal {@link EndpointSelector} implementation. This abstract class implements the
 * {@link #select(ClientRequestContext, ScheduledExecutorService)} method by listening to
 * the change events emitted by {@link EndpointGroup} specified at construction time.
 */
public abstract class AbstractEndpointSelector implements EndpointSelector {

    private final EndpointGroup endpointGroup;
    private final ReentrantShortLock lock = new ReentrantShortLock();
    @GuardedBy("lock")
    private final Set<ListeningFuture> pendingFutures =
            new ObjectLinkedOpenCustomHashSet<>(IdentityHashStrategy.of());

    /**
     * Creates a new instance that selects an {@link Endpoint} from the specified {@link EndpointGroup}.
     */
    protected AbstractEndpointSelector(EndpointGroup endpointGroup) {
        this.endpointGroup = requireNonNull(endpointGroup, "endpointGroup");
    }

    /**
     * Returns the {@link EndpointGroup} being selected by this {@link EndpointSelector}.
     */
    protected final EndpointGroup group() {
        return endpointGroup;
    }

    @SuppressWarnings("GuardedBy")
    @VisibleForTesting
    final Set<ListeningFuture> pendingFutures() {
        return pendingFutures;
    }

    @Deprecated
    @Override
    public final CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                                    ScheduledExecutorService executor,
                                                    long timeoutMillis) {
        return select(ctx, executor);
    }

    @Override
    public final CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                                    ScheduledExecutorService executor) {
        final Endpoint endpoint = selectNow(ctx);
        if (endpoint != null) {
            return UnmodifiableFuture.completedFuture(endpoint);
        }

        final ListeningFuture listeningFuture = new ListeningFuture(ctx, executor);
        addPendingFuture(listeningFuture);

        // The EndpointGroup have just been updated after adding ListeningFuture.
        if (listeningFuture.isDone()) {
            return listeningFuture;
        }
        if (endpointGroup.whenReady().isDone()) {
            final Endpoint endpoint0 = selectNow(ctx);
            if (endpoint0 != null) {
                // The EndpointGroup have just been updated before adding ListeningFuture.
                listeningFuture.complete(endpoint0);
                return listeningFuture;
            }
        }

        final long selectionTimeoutMillis = endpointGroup.selectionTimeoutMillis();
        if (selectionTimeoutMillis == 0) {
            // A static EndpointGroup.
            return UnmodifiableFuture.completedFuture(null);
        }

        // Schedule the timeout task.
        if (selectionTimeoutMillis < Long.MAX_VALUE) {
            final ScheduledFuture<?> timeoutFuture = executor.schedule(() -> {
                final EndpointSelectionTimeoutException ex =
                        EndpointSelectionTimeoutException.get(endpointGroup, selectionTimeoutMillis);
                ClientPendingThrowableUtil.setPendingThrowable(ctx, ex);
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
     * Initialize this {@link EndpointSelector} to listen to the new endpoints emitted by the
     * {@link EndpointGroup}. The new endpoints will be passed to {@link #updateNewEndpoints(List)}.
     */
    @UnstableApi
    protected final void initialize() {
        endpointGroup.addListener(this::refreshEndpoints, true);
    }

    private void refreshEndpoints(List<Endpoint> endpoints) {
        // Allow subclasses to update the endpoints first.
        updateNewEndpoints(endpoints).handle((ignored, e) -> {
            lock.lock();
            try {
                pendingFutures.removeIf(ListeningFuture::tryComplete);
            } finally {
                lock.unlock();
            }
            return null;
        });
    }

    /**
     * Invoked when the {@link EndpointGroup} has been updated.
     */
    @UnstableApi
    protected CompletableFuture<Void> updateNewEndpoints(List<Endpoint> endpoints) {
        return UnmodifiableFuture.completedFuture(null);
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
    final class ListeningFuture extends CompletableFuture<Endpoint> {
        private final ClientRequestContext ctx;
        private final Executor executor;
        @Nullable
        private volatile Endpoint selectedEndpoint;
        @Nullable
        private volatile ScheduledFuture<?> timeoutFuture;

        ListeningFuture(ClientRequestContext ctx, Executor executor) {
            this.ctx = ctx;
            this.executor = executor;
        }

        /**
         * Returns {@code true} if an {@link Endpoint} has been selected.
         */
        public boolean tryComplete() {
            if (selectedEndpoint != null || isDone()) {
                return true;
            }

            try {
                final Endpoint endpoint = selectNow(ctx);
                if (endpoint == null) {
                    return false;
                }

                cleanup(false);
                // Complete with the selected endpoint.
                selectedEndpoint = endpoint;
                executor.execute(() -> super.complete(endpoint));
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
        public boolean complete(Endpoint value) {
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
