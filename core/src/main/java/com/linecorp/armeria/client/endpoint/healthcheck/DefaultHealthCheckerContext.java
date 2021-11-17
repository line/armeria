/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.client.endpoint.healthcheck;

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
import java.util.function.BiConsumer;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;

final class DefaultHealthCheckerContext
        extends AbstractExecutorService implements HealthCheckerContext, ScheduledExecutorService {

    private final Endpoint originalEndpoint;
    private final Endpoint endpoint;
    private final SessionProtocol protocol;
    private final ClientOptions clientOptions;

    /**
     * Keeps the {@link Future}s which were scheduled via this {@link ScheduledExecutorService}.
     * Note that this field is also used as a lock.
     */
    private final Map<Future<?>, Boolean> scheduledFutures = new IdentityHashMap<>();
    private final CompletableFuture<Void> initialCheckFuture = new EventLoopCheckingFuture<>();
    private final Backoff retryBackoff;
    private final BiConsumer<Endpoint, Boolean> onUpdateHealth;

    @Nullable
    private AsyncCloseable handle;
    private boolean destroyed;
    private int refCnt = 1;

    DefaultHealthCheckerContext(Endpoint endpoint, int port, SessionProtocol protocol,
                                ClientOptions clientOptions, Backoff retryBackoff,
                                BiConsumer<Endpoint, Boolean> onUpdateHealth) {
        originalEndpoint = endpoint;

        if (port == 0) {
            this.endpoint = endpoint.withoutDefaultPort(protocol.defaultPort());
        } else if (port == protocol.defaultPort()) {
            this.endpoint = endpoint.withoutPort();
        } else {
            this.endpoint = endpoint.withPort(port);
        }
        this.protocol = protocol;
        this.clientOptions = clientOptions;
        this.retryBackoff = retryBackoff;
        this.onUpdateHealth = onUpdateHealth;
    }

    void init(AsyncCloseable handle) {
        assert this.handle == null;
        this.handle = handle;
    }

    boolean isInitialized() {
        return handle != null;
    }

    CompletableFuture<Void> whenInitialized() {
        return initialCheckFuture;
    }

    private CompletableFuture<Void> destroy() {
        assert handle != null : handle;
        return handle.closeAsync().handle((unused1, unused2) -> {
            synchronized (scheduledFutures) {
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
            }

            onUpdateHealth.accept(originalEndpoint, false);

            return null;
        });
    }

    @Override
    public Endpoint endpoint() {
        return endpoint;
    }

    @Override
    public SessionProtocol protocol() {
        return protocol;
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
                    "retryBackoff.nextDelayMillis(1) returned a negative value for " + endpoint +
                    ": " + delayMillis);
        }

        return delayMillis;
    }

    @Override
    public void updateHealth(double health) {
        onUpdateHealth.accept(originalEndpoint,  health > 0);

        if (!initialCheckFuture.isDone()) {
            initialCheckFuture.complete(null);
        }
    }

    @Override
    public void execute(Runnable command) {
        synchronized (scheduledFutures) {
            rejectIfDestroyed(command);
            add(eventLoopGroup().submit(command));
        }
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        synchronized (scheduledFutures) {
            rejectIfDestroyed(command);
            return add(eventLoopGroup().schedule(command, delay, unit));
        }
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        synchronized (scheduledFutures) {
            rejectIfDestroyed(callable);
            return add(eventLoopGroup().schedule(callable, delay, unit));
        }
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                  TimeUnit unit) {
        synchronized (scheduledFutures) {
            rejectIfDestroyed(command);
            return add(eventLoopGroup().scheduleAtFixedRate(command, initialDelay, period, unit));
        }
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                     TimeUnit unit) {
        synchronized (scheduledFutures) {
            rejectIfDestroyed(command);
            return add(eventLoopGroup().scheduleWithFixedDelay(command, initialDelay, delay, unit));
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
                    HealthCheckerContext.class.getSimpleName() + " for '" + endpoint +
                    "' has been destroyed already. Task: " + command);
        }
    }

    private <T extends Future<U>, U> T add(T future) {
        scheduledFutures.put(future, Boolean.TRUE);
        future.addListener(f -> {
            synchronized (scheduledFutures) {
                scheduledFutures.remove(f);
            }
        });
        return future;
    }

    int refCnt() {
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
}
