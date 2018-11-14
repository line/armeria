/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client;

import java.lang.reflect.Array;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

final class HttpChannelPool implements AutoCloseable {

    private final EventLoop eventLoop;
    private boolean closed;

    // Fields for pooling connections:
    private final Map<PoolKey, Deque<PooledChannel>>[] pool;
    private final Map<PoolKey, Future<Channel>>[] pendingConnections;
    private final Map<Channel, Boolean> allChannels;
    private final SafeConnectionPoolListener channelPoolHandler;

    // Fields for creating a new connection:
    private final Bootstrap[] bootstraps;
    private final int connectTimeoutMillis;

    HttpChannelPool(HttpClientFactory clientFactory, EventLoop eventLoop,
                    ConnectionPoolListener channelPoolHandler) {
        this.eventLoop = eventLoop;
        pool = newEnumMap(
                Map.class,
                unused -> new HashMap<>(),
                SessionProtocol.H1, SessionProtocol.H1C,
                SessionProtocol.H2, SessionProtocol.H2C);
        pendingConnections = newEnumMap(
                Map.class,
                unused -> new HashMap<>(),
                SessionProtocol.HTTP, SessionProtocol.HTTPS,
                SessionProtocol.H1, SessionProtocol.H1C,
                SessionProtocol.H2, SessionProtocol.H2C);
        allChannels = new IdentityHashMap<>();
        this.channelPoolHandler = new SafeConnectionPoolListener(channelPoolHandler);

        final Bootstrap baseBootstrap = clientFactory.newBootstrap();
        baseBootstrap.group(eventLoop);
        bootstraps = newEnumMap(
                Bootstrap.class,
                desiredProtocol -> {
                    final Bootstrap bootstrap = baseBootstrap.clone();
                    bootstrap.handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ch.pipeline().addLast(
                                    new HttpClientPipelineConfigurator(clientFactory, desiredProtocol));
                        }
                    });
                    return bootstrap;
                },
                SessionProtocol.HTTP, SessionProtocol.HTTPS,
                SessionProtocol.H1, SessionProtocol.H1C,
                SessionProtocol.H2, SessionProtocol.H2C);
        connectTimeoutMillis = (Integer) baseBootstrap.config().options()
                                                      .get(ChannelOption.CONNECT_TIMEOUT_MILLIS);
    }

    /**
     * Returns an array whose index signifies {@link SessionProtocol#ordinal()}.
     */
    private static <T> T[] newEnumMap(Class<?> elementType,
                                      Function<SessionProtocol, T> factory,
                                      SessionProtocol... allowedProtocols) {
        @SuppressWarnings("unchecked")
        final T[] maps = (T[]) Array.newInstance(elementType, SessionProtocol.values().length);
        // Attempting to access the array with an unallowed protocol will trigger NPE,
        // which will help us find a bug.
        for (SessionProtocol p : allowedProtocols) {
            maps[p.ordinal()] = factory.apply(p);
        }
        return maps;
    }

    private Bootstrap getBootstrap(SessionProtocol desiredProtocol) {
        return bootstraps[desiredProtocol.ordinal()];
    }

    @Nullable
    private Deque<PooledChannel> getPool(SessionProtocol protocol, PoolKey key) {
        return pool[protocol.ordinal()].get(key);
    }

    private Deque<PooledChannel> getOrCreatePool(SessionProtocol protocol, PoolKey key) {
        return pool[protocol.ordinal()].computeIfAbsent(key, k -> new ArrayDeque<>());
    }

    @Nullable
    private Future<Channel> getPendingConnection(SessionProtocol desiredProtocol, PoolKey key) {
        return pendingConnections[desiredProtocol.ordinal()].get(key);
    }

    private void setPendingConnection(SessionProtocol desiredProtocol, PoolKey key, Future<Channel> future) {
        pendingConnections[desiredProtocol.ordinal()].put(key, future);
    }

    private void removePendingConnection(SessionProtocol desiredProtocol, PoolKey key) {
        pendingConnections[desiredProtocol.ordinal()].remove(key);
    }

    /**
     * Acquires a {@link Channel} which is matched by the specified condition.
     */
    CompletableFuture<PooledChannel> acquire(SessionProtocol desiredProtocol, PoolKey key) {
        final CompletableFuture<PooledChannel> promise = new CompletableFuture<>();
        acquire(desiredProtocol, key, promise);
        return promise;
    }

    private void acquire(SessionProtocol desiredProtocol, PoolKey key,
                         CompletableFuture<PooledChannel> promise) {
        final PooledChannel ch = acquireNow(desiredProtocol, key);
        if (ch != null) {
            promise.complete(ch);
            return;
        }

        // Try to use the pending connection to avoid creating an extra connection.
        final Future<Channel> pendingChannel = getPendingConnection(desiredProtocol, key);
        if (pendingChannel != null) {
            // Acquire again after the pending connection is completed.
            pendingChannel.addListener(unused -> acquire(desiredProtocol, key, promise));
            return;
        }

        // Create a new connection.
        final Future<Channel> f = connect(desiredProtocol, key);
        setPendingConnection(desiredProtocol, key, f);
        if (f.isDone()) {
            notifyConnect(desiredProtocol, key, f, promise);
        } else {
            f.addListener((Future<Channel> future) -> notifyConnect(desiredProtocol, key, future, promise));
        }
    }

    /**
     * Attempts to acquire a {@link Channel} which is matched by the specified condition immediately.
     *
     * @return {@code null} is there's no match left in the pool and thus a new connection has to be
     *         requested via {@link #acquire(SessionProtocol, PoolKey)}.
     */
    @Nullable
    PooledChannel acquireNow(SessionProtocol desiredProtocol, PoolKey key) {
        PooledChannel ch;
        switch (desiredProtocol) {
            case HTTP:
                ch = acquireNowExact(key, SessionProtocol.H2C);
                if (ch == null) {
                    ch = acquireNowExact(key, SessionProtocol.H1C);
                }
                break;
            case HTTPS:
                ch = acquireNowExact(key, SessionProtocol.H2);
                if (ch == null) {
                    ch = acquireNowExact(key, SessionProtocol.H1);
                }
                break;
            default:
                ch = acquireNowExact(key, desiredProtocol);
        }
        return ch;
    }

    @Nullable
    private PooledChannel acquireNowExact(PoolKey key, SessionProtocol protocol) {
        final Deque<PooledChannel> queue = getPool(protocol, key);
        if (queue == null) {
            return null;
        }

        // Find the most recently released channel while cleaning up the unhealthy channels.
        for (;;) {
            final PooledChannel pooledChannel = queue.peekLast();
            if (pooledChannel == null) {
                return null;
            }

            if (!isHealthy(pooledChannel)) {
                queue.removeLast();
                continue;
            }

            if (!protocol.isMultiplex()) {
                queue.removeLast();
            }
            return pooledChannel;
        }
    }

    private static boolean isHealthy(PooledChannel pooledChannel) {
        final Channel ch = pooledChannel.get();
        return ch.isActive() && HttpSession.get(ch).isActive();
    }

    @Nullable
    private static SessionProtocol getProtocolIfHealthy(Channel ch) {
        if (!ch.isActive()) {
            return null;
        }

        return HttpSession.get(ch).protocol();
    }

    private static void closeChannel(Channel channel) {
        if (channel.isActive()) {
            channel.close();
        }
    }

    private Future<Channel> connect(SessionProtocol desiredProtocol, PoolKey key) {
        final InetSocketAddress remoteAddress;
        try {
            remoteAddress = toRemoteAddress(key);
        } catch (UnknownHostException e) {
            return eventLoop.newFailedFuture(e);
        }

        if (SessionProtocolNegotiationCache.isUnsupported(remoteAddress, desiredProtocol)) {
            // Fail immediately if it is sure that the remote address does not support the requested protocol.
            return eventLoop.newFailedFuture(
                    new SessionProtocolNegotiationException(desiredProtocol, "previously failed negotiation"));
        }

        final Promise<Channel> sessionPromise = eventLoop.newPromise();
        connect(remoteAddress, desiredProtocol, sessionPromise);

        return sessionPromise;
    }

    /**
     * A low-level operation that triggers a new connection attempt. Used only by:
     * <ul>
     *   <li>{@link #connect(SessionProtocol, PoolKey)} - The pool has been exhausted.</li>
     *   <li>{@link HttpSessionHandler} - HTTP/2 upgrade has failed.</li>
     * </ul>
     */
    void connect(SocketAddress remoteAddress, SessionProtocol desiredProtocol,
                 Promise<Channel> sessionPromise) {
        final Bootstrap bootstrap = getBootstrap(desiredProtocol);
        final ChannelFuture connectFuture = bootstrap.connect(remoteAddress);

        connectFuture.addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                initSession(desiredProtocol, future, sessionPromise);
            } else {
                sessionPromise.setFailure(future.cause());
            }
        });
    }

    private static InetSocketAddress toRemoteAddress(PoolKey key) throws UnknownHostException {
        final InetAddress inetAddr = InetAddress.getByAddress(
                key.host, NetUtil.createByteArrayFromIpAddressString(key.ipAddr));
        return new InetSocketAddress(inetAddr, key.port);
    }

    private void initSession(SessionProtocol desiredProtocol, ChannelFuture connectFuture,
                             Promise<Channel> sessionPromise) {
        assert connectFuture.isSuccess();

        final Channel ch = connectFuture.channel();
        final EventLoop eventLoop = ch.eventLoop();
        assert eventLoop.inEventLoop();

        final ScheduledFuture<?> timeoutFuture = eventLoop.schedule(() -> {
            if (sessionPromise.tryFailure(new SessionProtocolNegotiationException(
                    desiredProtocol, "connection established, but session creation timed out: " + ch))) {
                ch.close();
            }
        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);

        ch.pipeline().addLast(new HttpSessionHandler(this, ch, sessionPromise, timeoutFuture));
    }

    private void notifyConnect(SessionProtocol desiredProtocol, PoolKey key,
                               Future<Channel> future, CompletableFuture<PooledChannel> promise) {
        assert future.isDone();
        removePendingConnection(desiredProtocol, key);

        try {
            if (future.isSuccess()) {
                final Channel channel = future.getNow();
                final SessionProtocol protocol = getProtocolIfHealthy(channel);
                if (closed || protocol == null) {
                    channel.close();
                    promise.completeExceptionally(ClosedSessionException.get());
                    return;
                }

                channelPoolHandler.connectionOpen(protocol,
                                                  (InetSocketAddress) channel.remoteAddress(),
                                                  (InetSocketAddress) channel.localAddress(),
                                                  channel);
                allChannels.put(channel, Boolean.TRUE);

                if (protocol.isMultiplex()) {
                    final Http2PooledChannel pooledChannel = new Http2PooledChannel(channel, protocol);
                    addToPool(protocol, key, pooledChannel);
                    promise.complete(pooledChannel);
                } else {
                    promise.complete(new Http1PooledChannel(channel, protocol, key));
                }

                channel.closeFuture().addListener(f -> {
                    channelPoolHandler.connectionClosed(protocol,
                                                        (InetSocketAddress) channel.remoteAddress(),
                                                        (InetSocketAddress) channel.localAddress(),
                                                        channel);
                    allChannels.remove(channel);

                    // Clean up old unhealthy channels by iterating from the beginning of the queue.
                    final Deque<PooledChannel> queue = getPool(protocol, key);
                    if (queue != null) {
                        for (;;) {
                            final PooledChannel pooledChannel = queue.peekFirst();
                            if (pooledChannel == null || isHealthy(pooledChannel)) {
                                break;
                            }
                            queue.removeFirst();
                        }
                    }
                });
            } else {
                promise.completeExceptionally(future.cause());
            }
        } catch (Exception e) {
            promise.completeExceptionally(e);
        }
    }

    /**
     * Adds a {@link Channel} to this pool.
     */
    private void addToPool(SessionProtocol actualProtocol, PoolKey key, PooledChannel pooledChannel) {
        getOrCreatePool(actualProtocol, key).addLast(pooledChannel);
    }

    /**
     * Closes all {@link Channel}s managed by this pool.
     */
    @Override
    public void close() {
        closed = true;

        if (eventLoop.inEventLoop()) {
            // While we'd prefer to block until the pool is actually closed, we cannot block for the channels to
            // close if it was called from the event loop or we would deadlock. In practice, it's rare to call
            // close from an event loop thread, and not a main thread.
            doCloseAsync();
        } else {
            doCloseSync();
        }
    }

    private void doCloseAsync() {
        if (allChannels.isEmpty()) {
            return;
        }

        final List<ChannelFuture> closeFutures = new ArrayList<>(allChannels.size());
        for (Channel ch : allChannels.keySet()) {
            // NB: Do not call close() here, because it will trigger the closeFuture listener
            //     which mutates allChannels.
            closeFutures.add(ch.closeFuture());
        }

        closeFutures.forEach(f -> f.channel().close());
    }

    private void doCloseSync() {
        final CountDownLatch outerLatch = eventLoop.submit(() -> {
            if (allChannels.isEmpty()) {
                return null;
            }

            final int numChannels = allChannels.size();
            final CountDownLatch latch = new CountDownLatch(numChannels);
            if (numChannels == 0) {
                return latch;
            }
            final List<ChannelFuture> closeFutures = new ArrayList<>(numChannels);
            for (Channel ch : allChannels.keySet()) {
                // NB: Do not call close() here, because it will trigger the closeFuture listener
                //     which mutates allChannels.
                final ChannelFuture f = ch.closeFuture();
                closeFutures.add(f);
                f.addListener((ChannelFutureListener) future -> latch.countDown());
            }
            closeFutures.forEach(f -> f.channel().close());
            return latch;
        }).syncUninterruptibly().getNow();

        if (outerLatch != null) {
            boolean interrupted = false;
            while (outerLatch.getCount() != 0) {
                try {
                    outerLatch.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class PoolKey {
        final String host;
        final String ipAddr;
        final int port;
        final int hashCode;

        PoolKey(String host, String ipAddr, int port) {
            this.host = host;
            this.ipAddr = ipAddr;
            this.port = port;
            hashCode = ipAddr.hashCode() * 31 + port;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof PoolKey)) {
                return false;
            }

            final PoolKey that = (PoolKey) o;
            // Compare IP address first, which is most likely to differ.
            return ipAddr.equals(that.ipAddr) &&
                   port == that.port &&
                   host.equals(that.host);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("host", host)
                              .add("ipAddr", ipAddr)
                              .add("port", port)
                              .toString();
        }
    }

    static final class Http2PooledChannel extends PooledChannel {
        Http2PooledChannel(Channel channel, SessionProtocol protocol) {
            super(channel, protocol);
        }

        @Override
        public void release() {
            // There's nothing to do here because we keep the connection in the pool after acquisition.
        }
    }

    final class Http1PooledChannel extends PooledChannel {
        private final PoolKey key;

        Http1PooledChannel(Channel channel, SessionProtocol protocol, PoolKey key) {
            super(channel, protocol);
            this.key = key;
        }

        @Override
        public void release() {
            if (isHealthy(this)) {
                // Channel turns out to be healthy, offering and releasing it.
                addToPool(protocol(), key, this);
            } else {
                // Channel not healthy, just releasing it.
                closeChannel(get());
            }
        }
    }
}
