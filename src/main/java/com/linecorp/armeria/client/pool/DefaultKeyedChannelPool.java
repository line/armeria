/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.pool;

import static java.util.Objects.requireNonNull;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;

import com.linecorp.armeria.common.util.Exceptions;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
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
            Exceptions.clearTrace(new IllegalStateException("Channel is unhealthy; not offering it back to pool"));

    private final EventLoop eventLoop;
    private final Function<K, Future<Channel>> channelFactory;
    private final ChannelHealthChecker healthCheck;
    private final KeyedChannelPoolHandler<K> channelPoolHandler;
    private final boolean releaseHealthCheck;

    private final Map<K, Deque<Channel>> pool;

    /**
     * Creates a new instance.
     */
    public DefaultKeyedChannelPool(EventLoop eventLoop, Function<K, Future<Channel>> channelFactory,
                                   KeyedChannelPoolHandler<K> channelPoolHandler) {
        this(eventLoop, channelFactory, ChannelHealthChecker.ACTIVE, channelPoolHandler, true);
    }

    /**
     * Creates a new instance.
     */
    public DefaultKeyedChannelPool(EventLoop eventLoop, Function<K, Future<Channel>> channelFactory,
                                   ChannelHealthChecker healthCheck,
                                   KeyedChannelPoolHandler<K> channelPoolHandler) {
        this(eventLoop, channelFactory, healthCheck, channelPoolHandler, true);
    }

    /**
     * Creates a new instance.
     */
    public DefaultKeyedChannelPool(EventLoop eventLoop, Function<K, Future<Channel>> channelFactory,
                                   ChannelHealthChecker healthCheck,
                                   KeyedChannelPoolHandler<K> channelPoolHandler,
                                   boolean releaseHealthCheck) {
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        this.channelFactory = requireNonNull(channelFactory, "channelFactory");
        this.healthCheck = requireNonNull(healthCheck, "healthCheck");
        this.channelPoolHandler = new SafeKeyedChannelPoolHandler<>(requireNonNull(channelPoolHandler,
                                                                                   "channelPoolHandler"));
        this.releaseHealthCheck = releaseHealthCheck;

        pool = new ConcurrentHashMap<>();
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
        final Deque<Channel> queue = pool.get(key);
        final Channel ch = queue == null ? null : queue.poll();

        if (ch == null) {
            Future<Channel> f = channelFactory.apply(key);
            if (f.isDone()) {
                notifyConnect(key, f, promise);
            } else {
                f.addListener((Future<Channel> future) -> notifyConnect(key, future, promise));
            }
            return promise;
        }

        EventLoop loop = ch.eventLoop();
        if (loop.inEventLoop()) {
            doHealthCheck(key, ch, promise);
        } else {
            loop.execute(() -> doHealthCheck(key, ch, promise));
        }

        return promise;
    }

    private void notifyConnect(K key, Future<Channel> future, Promise<Channel> promise) {
        assert future.isDone();

        try {
            if (future.isSuccess()) {
                Channel channel = future.getNow();
                channel.attr(KeyedChannelPoolUtil.POOL).set(this);
                channelPoolHandler.channelCreated(key, channel);
                channel.closeFuture().addListener(f -> channelPoolHandler.channelClosed(key, channel));
                promise.setSuccess(channel);
            } else {
                promise.setFailure(future.cause());
            }
        } catch (Exception e) {
            promise.setFailure(e);
        }
    }

    private void doHealthCheck(final K key, final Channel ch, final Promise<Channel> promise) {
        assert ch.eventLoop().inEventLoop();

        Future<Boolean> f = healthCheck.isHealthy(ch);
        if (f.isDone()) {
            notifyHealthCheck(key, f, ch, promise);
        } else {
            f.addListener((FutureListener<Boolean>) future -> notifyHealthCheck(key, future, ch, promise));
        }
    }

    private void notifyHealthCheck(final K key, Future<Boolean> future, Channel ch, Promise<Channel> promise) {
        assert ch.eventLoop().inEventLoop();

        if (future.isSuccess()) {
            if (future.getNow() == Boolean.TRUE) {
                try {
                    ch.attr(KeyedChannelPoolUtil.POOL).set(this);
                    channelPoolHandler.channelAcquired(key, ch);
                    promise.setSuccess(ch);
                } catch (Throwable cause) {
                    closeAndFail(ch, cause, promise);
                }
            } else {
                closeChannel(ch);
                acquireHealthyFromPoolOrNew(key, promise);
            }
        } else {
            closeChannel(ch);
            acquireHealthyFromPoolOrNew(key, promise);
        }
    }

    private static void closeChannel(Channel channel) {
        channel.attr(KeyedChannelPoolUtil.POOL).set(null);
        channel.close();
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
            EventLoop loop = channel.eventLoop();
            if (loop.inEventLoop()) {
                doReleaseChannel(key, channel, promise);
            } else {
                loop.execute(() -> doReleaseChannel(key, channel, promise));
            }
        } catch (Throwable cause) {
            closeAndFail(channel, cause, promise);
        }
        return promise;
    }

    private void doReleaseChannel(K key, Channel channel, Promise<Void> promise) {
        assert channel.eventLoop().inEventLoop();
        if (channel.attr(KeyedChannelPoolUtil.POOL).getAndSet(null) != this) {
            // Better include a stracktrace here as this is an user error.
            closeAndFail(channel, new IllegalArgumentException(
                    "Channel " + channel + " was not acquired from this ChannelPool"), promise);
        } else {
            try {
                if (releaseHealthCheck) {
                    doHealthCheckOnRelease(key, channel, promise);
                } else {
                    releaseAndOffer(key, channel, promise);
                }
            } catch (Throwable cause) {
                closeAndFail(channel, cause, promise);
            }
        }
    }

    private void doHealthCheckOnRelease(K key, final Channel channel, final Promise<Void> promise)
            throws Exception {
        final Future<Boolean> f = healthCheck.isHealthy(channel);
        if (f.isDone()) {
            releaseAndOfferIfHealthy(key, channel, promise, f);
        } else {
            f.addListener(future -> releaseAndOfferIfHealthy(key, channel, promise, f));
        }
    }

    private void releaseAndOfferIfHealthy(K key, Channel channel, Promise<Void> promise, Future<Boolean> future)
            throws Exception {
        if (future.getNow()) { //channel turns out to be healthy, offering and releasing it.
            releaseAndOffer(key, channel, promise);
        } else { //channel ont healthy, just releasing it.
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
     * Removes a {@link Channel} that matches the specified {@code key} from this pool.
     *
     * @return the removed {@link Channel}. {@code null} if there's no matching {@link Channel}.
     */
    protected Channel pollChannel(K key) {
        final Deque<Channel> queue = pool.get(key);
        final Channel ch;
        if (queue == null) {
            ch = null;
        } else {
            ch = queue.poll();
            if (queue.isEmpty()) {
                pool.remove(key);
            }
        }
        return ch;
    }

    /**
     * Adds a {@link Channel} to this pool.
     *
     * @return whether adding the {@link Channel} has succeeded or not
     */
    protected boolean offerChannel(K key, Channel channel) {
        return pool.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>()).offer(channel);
    }

    @Override
    public void close() {
        pool.forEach((k, v) -> {
            for (;;) {
                Channel channel = pollChannel(k);
                if (channel == null) {
                    break;
                }

                if (channel.isOpen()) {
                    channel.close();
                }
            }
        });
    }
}
