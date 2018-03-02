/*
 * Copyright 2015 LINE Corporation
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
package com.linecorp.armeria.client.pool;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

/**
 * Default {@link KeyedChannelPool} implementation.
 *
 * @param <K> the key type
 */
public class DefaultKeyedChannelPool<K> implements KeyedChannelPool<K> {

    private static final IllegalStateException FULL_EXCEPTION =
            Exceptions.clearTrace(new IllegalStateException("ChannelPool full"));

    private static final IllegalStateException UNHEALTHY_NON_OFFERED_TO_POOL =
            Exceptions.clearTrace(new IllegalStateException(
                    "Channel is unhealthy; not offering it back to pool"));

    private final EventLoop eventLoop;
    private final Function<K, Future<Channel>> channelFactory;
    private final Predicate<Channel> healthChecker;
    private final KeyedChannelPoolHandler<K> channelPoolHandler;
    private final boolean healthCheckOnRelease;

    private final Map<K, Deque<Channel>> pool;
    private final Map<K, Future<Channel>> pendingConnections;

    private final Set<Channel> allChannels;

    private boolean closed;

    /**
     * Creates a new instance.
     */
    public DefaultKeyedChannelPool(EventLoop eventLoop, Function<K, Future<Channel>> channelFactory,
                                   Predicate<Channel> healthChecker,
                                   KeyedChannelPoolHandler<K> channelPoolHandler,
                                   boolean healthCheckOnRelease) {
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        this.channelFactory = requireNonNull(channelFactory, "channelFactory");
        this.healthChecker = requireNonNull(healthChecker, "healthChecker");
        this.channelPoolHandler = new SafeKeyedChannelPoolHandler<>(requireNonNull(channelPoolHandler,
                                                                                   "channelPoolHandler"));
        this.healthCheckOnRelease = healthCheckOnRelease;

        pool = new HashMap<>();
        pendingConnections = new HashMap<>();
        allChannels = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    @Override
    public Future<Channel> acquire(K key) {
        return acquire(key, eventLoop.newPromise());
    }

    @Override
    public Future<Channel> acquire(final K key, final Promise<Channel> promise) {
        requireNonNull(key, "key");
        requireNonNull(promise, "promise");

        if (eventLoop.inEventLoop()) {
            acquireHealthyFromPoolOrNew(key, promise);
        } else {
            eventLoop.execute(() -> acquireHealthyFromPoolOrNew(key, promise));
        }

        return promise;
    }

    private Future<Channel> acquireHealthyFromPoolOrNew(final K key, final Promise<Channel> promise) {
        assert eventLoop.inEventLoop();

        if (closed) {
            promise.setFailure(ClosedSessionException.get());
            return promise;
        }

        final Channel ch = pollHealthy(key);
        if (ch == null) {
            final Future<Channel> pendingChannel = pendingConnections.get(key);
            if (pendingChannel != null) {
                // Try acquiring again after the pending connection is completed.
                pendingChannel.addListener(unused -> acquireHealthyFromPoolOrNew(key, promise));
            } else {
                final Future<Channel> f = channelFactory.apply(key);
                pendingConnections.put(key, f);
                if (f.isDone()) {
                    notifyConnect(key, f, promise);
                } else {
                    f.addListener((Future<Channel> future) -> notifyConnect(key, future, promise));
                }
            }
        } else {
            try {
                ch.attr(KeyedChannelPoolUtil.POOL).set(this);
                channelPoolHandler.channelAcquired(key, ch);
                promise.setSuccess(ch);
            } catch (Throwable cause) {
                closeAndFail(ch, cause, promise);
            }
        }

        return promise;
    }

    @Nullable
    private Channel pollHealthy(K key) {
        final Deque<Channel> queue = pool.get(key);
        if (queue == null) {
            return null;
        }

        // Find the most recently released channel while cleaning up the unhealthy channels from the both ends.
        for (;;) {
            final Channel ch = queue.pollLast();
            if (ch == null) {
                return null;
            }

            if (healthChecker.test(ch)) {
                removeUnhealthy(queue);
                return ch;
            }

            closeChannel(ch);
        }
    }

    void removeUnhealthy(Deque<Channel> queue) {
        if (!queue.isEmpty()) {
            for (final Iterator<Channel> i = queue.iterator(); i.hasNext();) {
                final Channel ch = i.next();
                if (healthChecker.test(ch)) {
                    break;
                } else {
                    i.remove();
                    closeChannel(ch);
                }
            }
        }
    }

    private void notifyConnect(K key, Future<Channel> future, Promise<Channel> promise) {
        assert future.isDone();
        pendingConnections.remove(key);

        try {
            if (future.isSuccess()) {
                final Channel channel = future.getNow();
                if (closed) {
                    channel.close();
                    promise.setFailure(ClosedSessionException.get());
                    return;
                }

                channel.attr(KeyedChannelPoolUtil.POOL).set(this);
                channelPoolHandler.channelCreated(key, channel);
                allChannels.add(channel);
                channel.closeFuture().addListener(f -> {
                    channelPoolHandler.channelClosed(key, channel);
                    allChannels.remove(channel);
                    final Deque<Channel> queue = pool.get(key);
                    if (queue != null) {
                        removeUnhealthy(queue);
                        // NB: There's no race between pool.remove(), pool.computeIfAbsent() and queue.offer*()
                        //     because they always run in the same thread.
                        if (queue.isEmpty()) {
                            pool.remove(key);
                        }
                    }
                });
                promise.setSuccess(channel);
            } else {
                promise.setFailure(future.cause());
            }
        } catch (Exception e) {
            promise.setFailure(e);
        }
    }

    private static void closeChannel(Channel channel) {
        channel.attr(KeyedChannelPoolUtil.POOL).set(null);
        if (channel.isOpen()) {
            channel.close();
        }
    }

    private static void closeAndFail(Channel channel, Throwable cause, Promise<?> promise) {
        closeChannel(channel);
        promise.setFailure(cause);
    }

    @Override
    public Future<Void> release(K key, Channel channel) {
        return release(key, channel, eventLoop.newPromise());
    }

    @Override
    public Future<Void> release(final K key, final Channel channel, final Promise<Void> promise) {
        requireNonNull(key, "key");
        requireNonNull(channel, "channel");
        requireNonNull(promise, "promise");

        try {
            final EventLoop loop = channel.eventLoop();
            if (loop.inEventLoop()) {
                doRelease(key, channel, promise);
            } else {
                loop.execute(() -> doRelease(key, channel, promise));
            }
        } catch (Throwable cause) {
            closeAndFail(channel, cause, promise);
        }
        return promise;
    }

    private void doRelease(K key, Channel channel, Promise<Void> promise) {
        assert channel.eventLoop().inEventLoop();
        if (channel.attr(KeyedChannelPoolUtil.POOL).getAndSet(null) != this) {
            // Better including a stack trace here as this is a user error.
            closeAndFail(channel, new IllegalArgumentException(
                    "Channel " + channel + " was not acquired from this ChannelPool"), promise);
        } else {
            try {
                if (healthCheckOnRelease) {
                    healthCheckOnRelease(key, channel, promise);
                } else {
                    releaseAndOffer(key, channel, promise);
                }
            } catch (Throwable cause) {
                closeAndFail(channel, cause, promise);
            }
        }
    }

    private void healthCheckOnRelease(K key, final Channel channel, final Promise<Void> promise)
            throws Exception {
        if (healthChecker.test(channel)) {
            // Channel turns out to be healthy, offering and releasing it.
            releaseAndOffer(key, channel, promise);
        } else {
            // Channel not healthy, just releasing it.
            channelPoolHandler.channelReleased(key, channel);
            closeAndFail(channel, UNHEALTHY_NON_OFFERED_TO_POOL, promise);
        }
    }

    private void releaseAndOffer(K key, Channel channel, Promise<Void> promise) throws Exception {
        if (offerChannel(key, channel)) {
            channelPoolHandler.channelReleased(key, channel);
            promise.setSuccess(null);
        } else {
            closeAndFail(channel, FULL_EXCEPTION, promise);
        }
    }

    /**
     * Adds a {@link Channel} to this pool.
     *
     * @return whether adding the {@link Channel} has succeeded or not
     */
    protected boolean offerChannel(K key, Channel channel) {
        return pool.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>()).offerLast(channel);
    }

    @Override
    public void close() {
        if (eventLoop.inEventLoop()) {
            // While we'd prefer to block until the pool is actually closed, we cannot block for the channels to
            // close if it was called from the event loop or we would deadlock. In practice, it's rare to call
            // close from an event loop thread, and not a main thread.
            doClose(false);
        } else {
            eventLoop.submit(() -> doClose(true)).syncUninterruptibly();
        }
    }

    private void doClose(boolean blocking) {
        closed = true;

        if (allChannels.isEmpty()) {
            return;
        }

        final List<ChannelFuture> closeFutures = new ArrayList<>(allChannels.size());
        for (Channel ch : allChannels) {
            if (ch.isOpen()) {
                // NB: Do not call close() here, because it will trigger the closeFuture listener
                //     which mutates allChannels.
                closeFutures.add(ch.closeFuture());
            }
        }

        closeFutures.forEach(f -> f.channel().close());
        if (blocking) {
            closeFutures.forEach(ChannelFuture::syncUninterruptibly);
        }
    }
}
