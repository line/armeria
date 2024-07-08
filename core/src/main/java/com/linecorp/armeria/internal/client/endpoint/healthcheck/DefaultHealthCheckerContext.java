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

package com.linecorp.armeria.internal.client.endpoint.healthcheck;

import static com.linecorp.armeria.internal.client.endpoint.healthcheck.HealthCheckAttributes.DEGRADED_ATTR;
import static com.linecorp.armeria.internal.client.endpoint.healthcheck.HealthCheckAttributes.HEALTHY_ATTR;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.InvalidResponseException;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerContext;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerParams;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.Attributes;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;

public final class DefaultHealthCheckerContext
        extends AbstractExecutorService implements HealthCheckerContext, ScheduledExecutorService {

    private final Endpoint originalEndpoint;
    Function<Endpoint, HealthCheckerParams> paramsFactory;
    private volatile Attributes endpointAttributes;
    private final ClientOptions clientOptions;
    private final ReentrantLock lock = new ReentrantShortLock();

    /**
     * Keeps the {@link Future}s which were scheduled via this {@link ScheduledExecutorService}.
     * Note that this field is also used as a lock.
     */
    @GuardedBy("lock")
    private final Map<Future<?>, Boolean> scheduledFutures = new IdentityHashMap<>();
    private final CompletableFuture<Void> initialCheckFuture = new EventLoopCheckingFuture<>();
    private final Backoff retryBackoff;
    // endpoint, context, add or remove
    private final BiConsumer<Endpoint, Boolean> onUpdateHealth;

    @Nullable
    private AsyncCloseable handle;
    private boolean destroyed;
    private int refCnt = 1;

    DefaultHealthCheckerContext(Endpoint endpoint,
                                ClientOptions clientOptions, Backoff retryBackoff,
                                BiConsumer<Endpoint, Boolean> onUpdateHealth,
                                Function<Endpoint, HealthCheckerParams> paramsFactory) {
        originalEndpoint = endpoint;
        this.paramsFactory = paramsFactory;
        endpointAttributes = Attributes.of(HEALTHY_ATTR, false);

        this.clientOptions = clientOptions;
        this.retryBackoff = retryBackoff;
        this.onUpdateHealth = onUpdateHealth;
    }

    void init(AsyncCloseable handle) {
        assert this.handle == null;
        this.handle = handle;
    }

    boolean initializationStarted() {
        return handle != null;
    }

    CompletableFuture<Void> whenInitialized() {
        return initialCheckFuture;
    }

    private CompletableFuture<Void> destroy() {
        assert handle != null : handle;
        return handle.closeAsync().handle((unused1, unused2) -> {
            lock.lock();
            try {
                if (destroyed) {
                    return null;
                }

                destroyed = true;

                // Cancel all scheduled tasks. Make a copy to prevent ConcurrentModificationException
                // when the future's handler removes it from scheduledFutures as a result of
                // the cancellation, which may happen on this thread.
                if (!scheduledFutures.isEmpty()) {
                    final ImmutableList<Future<?>> copy = ImmutableList.copyOf(scheduledFutures.keySet());
                    copy.forEach(f -> f.cancel(false));
                }
            } finally {
                lock.unlock();
            }

            endpointAttributes = Attributes.of(HEALTHY_ATTR, false);
            onUpdateHealth.accept(originalEndpoint, false);

            return null;
        });
    }

    @Override
    public Endpoint endpoint() {
        return paramsFactory.apply(originalEndpoint).endpoint();
    }

    Attributes endpointAttributes() {
        return endpointAttributes;
    }

    @Override
    public SessionProtocol protocol() {
        return paramsFactory.apply(endpoint()).protocol();
    }

    @Override
    public ClientOptions clientOptions() {
        return clientOptions;
    }

    @Override
    public ScheduledExecutorService executor() {
        return this;
    }

    @Override
    public long nextDelayMillis() {
        final long delayMillis = retryBackoff.nextDelayMillis(1);
        if (delayMillis < 0) {
            throw new IllegalStateException(
                    "retryBackoff.nextDelayMillis(1) returned a negative value for " + originalEndpoint +
                    ": " + delayMillis);
        }

        return delayMillis;
    }

    @Override
    public void updateHealth(double health) {
        // Should use the new 'updateHealth()' API below.
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateHealth(double health, ClientRequestContext ctx,
                             @Nullable ResponseHeaders headers, @Nullable Throwable cause) {
        final boolean isHealthy = health > 0;

        if (headers != null && headers.contains("x-envoy-degraded")) {
            endpointAttributes = Attributes.of(HEALTHY_ATTR, isHealthy,
                                               DEGRADED_ATTR, true);
        } else {
            endpointAttributes = Attributes.of(HEALTHY_ATTR, isHealthy);
        }
        onUpdateHealth.accept(originalEndpoint, isHealthy);

        if (!initialCheckFuture.isDone()) {
            if (isHealthy) {
                initialCheckFuture.complete(null);
            } else {
                if (cause != null) {
                    initialCheckFuture.completeExceptionally(cause);
                } else {
                    assert headers != null;
                    initialCheckFuture.completeExceptionally(new InvalidResponseException(
                            ctx + " Received an unhealthy check response. headers: " + headers));
                }
            }
        }
    }

    @Override
    public Supplier<HealthCheckerParams> paramsFactory() {
        return () -> paramsFactory.apply(originalEndpoint);
    }

    @Override
    public void execute(Runnable command) {
        lock.lock();
        try {
            rejectIfDestroyed(command);
            add(eventLoopGroup().submit(command));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        lock.lock();
        try {
            rejectIfDestroyed(command);
            return add(eventLoopGroup().schedule(command, delay, unit));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        lock.lock();
        try {
            rejectIfDestroyed(callable);
            return add(eventLoopGroup().schedule(callable, delay, unit));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                  TimeUnit unit) {
        lock.lock();
        try {
            rejectIfDestroyed(command);
            return add(eventLoopGroup().scheduleAtFixedRate(command, initialDelay, period, unit));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                     TimeUnit unit) {
        lock.lock();
        try {
            rejectIfDestroyed(command);
            return add(eventLoopGroup().scheduleWithFixedDelay(command, initialDelay, delay, unit));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void shutdown() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Runnable> shutdownNow() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isShutdown() {
        return eventLoopGroup().isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return eventLoopGroup().isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return eventLoopGroup().awaitTermination(timeout, unit);
    }

    private EventLoopGroup eventLoopGroup() {
        return clientOptions.factory().eventLoopGroup();
    }

    private void rejectIfDestroyed(Object command) {
        if (destroyed) {
            throw new RejectedExecutionException(
                    HealthCheckerContext.class.getSimpleName() + " for '" + originalEndpoint +
                    "' has been destroyed already. Task: " + command);
        }
    }

    // The caller who calls this method must have the lock.
    @SuppressWarnings("GuardedBy")
    private <T extends Future<U>, U> T add(T future) {
        scheduledFutures.put(future, Boolean.TRUE);
        future.addListener(f -> {
            lock.lock();
            try {
                scheduledFutures.remove(f);
            } finally {
                lock.unlock();
            }
        });
        return future;
    }

    @VisibleForTesting
    public int refCnt() {
        return refCnt;
    }

    DefaultHealthCheckerContext retain() {
        if (destroyed) {
            throw new IllegalStateException("HealthCheckerContext is closed already");
        }
        refCnt++;
        return this;
    }

    @Nullable
    CompletableFuture<?> release() {
        assert refCnt > 0 : refCnt;

        if (--refCnt == 0) {
            return destroy();
        }
        return null;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("originalEndpoint", originalEndpoint)
                          .add("initializationStarted", initializationStarted())
                          .add("initialized", initialCheckFuture.isDone())
                          .add("destroyed", destroyed)
                          .add("refCnt", refCnt)
                          .toString();
    }
}
