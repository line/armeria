/*
 * Copyright 2018 LINE Corporation
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.proxy.ConnectProxyConfig;
import com.linecorp.armeria.client.proxy.HAProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyConfigSelector;
import com.linecorp.armeria.client.proxy.ProxyType;
import com.linecorp.armeria.client.proxy.Socks4ProxyConfig;
import com.linecorp.armeria.client.proxy.Socks5ProxyConfig;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.ClientConnectionTimingsBuilder;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import reactor.core.scheduler.NonBlocking;

final class HttpChannelPool implements AsyncCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HttpChannelPool.class);
    private static final Channel[] EMPTY_CHANNELS = new Channel[0];

    private final HttpClientFactory clientFactory;
    private final EventLoop eventLoop;
    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync);

    // Fields for pooling connections:
    private final Map<PoolKey, Deque<PooledChannel>>[] pool;
    private final Map<PoolKey, ChannelAcquisitionFuture>[] pendingAcquisitions;
    private final Map<Channel, Boolean> allChannels;
    private final ConnectionPoolListener listener;

    // Fields for creating a new connection:
    private final Bootstrap[] bootstraps;
    private final int connectTimeoutMillis;

    private final SslContext sslCtxHttp1Or2;
    private final SslContext sslCtxHttp1Only;

    HttpChannelPool(HttpClientFactory clientFactory, EventLoop eventLoop,
                    SslContext sslCtxHttp1Or2, SslContext sslCtxHttp1Only,
                    ConnectionPoolListener listener) {
        this.clientFactory = clientFactory;
        this.eventLoop = eventLoop;
        pool = newEnumMap(
                Map.class,
                unused -> new HashMap<>(),
                SessionProtocol.H1, SessionProtocol.H1C,
                SessionProtocol.H2, SessionProtocol.H2C);
        pendingAcquisitions = newEnumMap(
                Map.class,
                unused -> new HashMap<>(),
                SessionProtocol.HTTP, SessionProtocol.HTTPS,
                SessionProtocol.H1, SessionProtocol.H1C,
                SessionProtocol.H2, SessionProtocol.H2C);
        allChannels = new IdentityHashMap<>();
        this.listener = listener;
        this.sslCtxHttp1Only = sslCtxHttp1Only;
        this.sslCtxHttp1Or2 = sslCtxHttp1Or2;

        final Bootstrap baseBootstrap = clientFactory.newBootstrap();
        baseBootstrap.group(eventLoop);
        bootstraps = newEnumMap(
                Bootstrap.class,
                desiredProtocol -> {
                    final SslContext sslCtx = determineSslContext(desiredProtocol);
                    final Bootstrap bootstrap = baseBootstrap.clone();
                    bootstrap.handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ch.pipeline().addLast(
                                    new HttpClientPipelineConfigurator(clientFactory, desiredProtocol, sslCtx));
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

    private SslContext determineSslContext(SessionProtocol desiredProtocol) {
        return desiredProtocol == SessionProtocol.H1 || desiredProtocol == SessionProtocol.H1C ?
               sslCtxHttp1Only : sslCtxHttp1Or2;
    }

    private void configureProxy(Channel ch, ProxyConfig proxyConfig, SessionProtocol desiredProtocol) {
        if (proxyConfig.proxyType() == ProxyType.DIRECT) {
            return;
        }
        final ProxyHandler proxyHandler;
        final InetSocketAddress proxyAddress = proxyConfig.proxyAddress();
        assert proxyAddress != null;
        switch (proxyConfig.proxyType()) {
            case SOCKS4:
                final Socks4ProxyConfig socks4ProxyConfig = (Socks4ProxyConfig) proxyConfig;
                proxyHandler = new Socks4ProxyHandler(proxyAddress, socks4ProxyConfig.username());
                break;
            case SOCKS5:
                final Socks5ProxyConfig socks5ProxyConfig = (Socks5ProxyConfig) proxyConfig;
                proxyHandler = new Socks5ProxyHandler(proxyAddress, socks5ProxyConfig.username(),
                                                      socks5ProxyConfig.password());
                break;
            case CONNECT:
                final ConnectProxyConfig connectProxyConfig = (ConnectProxyConfig) proxyConfig;
                final String username = connectProxyConfig.username();
                final String password = connectProxyConfig.password();
                if (username == null || password == null) {
                    proxyHandler = new HttpProxyHandler(proxyAddress);
                } else {
                    proxyHandler = new HttpProxyHandler(proxyAddress, username, password);
                }
                break;
            case HAPROXY:
                ch.pipeline().addFirst(new HAProxyHandler((HAProxyConfig) proxyConfig));
                return;
            default:
                throw new Error(); // Should never reach here.
        }
        proxyHandler.setConnectTimeoutMillis(connectTimeoutMillis);
        ch.pipeline().addFirst(proxyHandler);

        if (proxyConfig instanceof ConnectProxyConfig && ((ConnectProxyConfig) proxyConfig).useTls()) {
            final SslContext sslCtx = determineSslContext(desiredProtocol);
            ch.pipeline().addFirst(sslCtx.newHandler(ch.alloc()));
        }
    }

    /**
     * Returns an array whose index signifies {@link SessionProtocol#ordinal()}. Similar to {@link EnumMap}.
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
    private ChannelAcquisitionFuture getPendingAcquisition(SessionProtocol desiredProtocol, PoolKey key) {
        return pendingAcquisitions[desiredProtocol.ordinal()].get(key);
    }

    private void setPendingAcquisition(SessionProtocol desiredProtocol, PoolKey key,
                                       ChannelAcquisitionFuture future) {
        pendingAcquisitions[desiredProtocol.ordinal()].put(key, future);
    }

    private void removePendingAcquisition(SessionProtocol desiredProtocol, PoolKey key) {
        pendingAcquisitions[desiredProtocol.ordinal()].remove(key);
    }

    /**
     * Attempts to acquire a {@link Channel} which is matched by the specified condition immediately.
     *
     * @return {@code null} is there's no match left in the pool and thus a new connection has to be
     *         requested via {@link #acquireLater(SessionProtocol, PoolKey, ClientConnectionTimingsBuilder)}.
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
        for (int i = queue.size(); i > 0; i--) {
            final PooledChannel pooledChannel = queue.peekLast();
            assert pooledChannel != null;
            if (!isHealthy(pooledChannel)) {
                queue.removeLast();
                continue;
            }

            final HttpSession session = HttpSession.get(pooledChannel.get());
            if (!session.incrementNumUnfinishedResponses()) {
                // The channel is full of streams so we cannot create a new one.
                // Move the channel to the beginning of the queue so it has low priority.
                queue.removeLast();
                queue.addFirst(pooledChannel);
                continue;
            }

            if (!protocol.isMultiplex()) {
                queue.removeLast();
            }
            return pooledChannel;
        }

        return null;
    }

    private static boolean isHealthy(PooledChannel pooledChannel) {
        final Channel ch = pooledChannel.get();
        return ch.isActive() && HttpSession.get(ch).canSendRequest();
    }

    @Nullable
    private static SessionProtocol getProtocolIfHealthy(Channel ch) {
        if (!ch.isActive()) {
            return null;
        }

        // Note that we do not need to check 'HttpSession.isActive()'
        // because an inactive session always returns null.
        return HttpSession.get(ch).protocol();
    }

    /**
     * Acquires a new {@link Channel} which is matched by the specified condition by making a connection
     * attempt or waiting for the current connection attempt in progress.
     */
    CompletableFuture<PooledChannel> acquireLater(SessionProtocol desiredProtocol, PoolKey key,
                                                  ClientConnectionTimingsBuilder timingsBuilder) {
        final ChannelAcquisitionFuture promise = new ChannelAcquisitionFuture();
        if (!usePendingAcquisition(desiredProtocol, key, promise, timingsBuilder)) {
            connect(desiredProtocol, key, promise, timingsBuilder);
        }
        return promise;
    }

    /**
     * Tries to use the pending HTTP/2 connection to avoid creating an extra connection.
     *
     * @return {@code true} if succeeded to reuse the pending connection.
     */
    private boolean usePendingAcquisition(SessionProtocol desiredProtocol, PoolKey key,
                                          ChannelAcquisitionFuture promise,
                                          ClientConnectionTimingsBuilder timingsBuilder) {

        if (desiredProtocol == SessionProtocol.H1 || desiredProtocol == SessionProtocol.H1C) {
            // Can't use HTTP/1 connections because they will not be available in the pool until
            // the request is done.
            return false;
        }

        final ChannelAcquisitionFuture pendingAcquisition = getPendingAcquisition(desiredProtocol, key);
        if (pendingAcquisition == null) {
            return false;
        }

        timingsBuilder.pendingAcquisitionStart();
        pendingAcquisition.piggyback(desiredProtocol, key, promise, timingsBuilder);
        return true;
    }

    private void connect(SessionProtocol desiredProtocol, PoolKey key, ChannelAcquisitionFuture promise,
                         ClientConnectionTimingsBuilder timingsBuilder) {
        setPendingAcquisition(desiredProtocol, key, promise);
        timingsBuilder.socketConnectStart();

        final InetSocketAddress remoteAddress;
        try {
            remoteAddress = toRemoteAddress(key);
        } catch (UnknownHostException e) {
            notifyConnect(desiredProtocol, key, eventLoop.newFailedFuture(e), promise, timingsBuilder);
            return;
        }

        // Fail immediately if it is sure that the remote address doesn't support the desired protocol.
        if (SessionProtocolNegotiationCache.isUnsupported(remoteAddress, desiredProtocol)) {
            notifyConnect(desiredProtocol, key,
                          eventLoop.newFailedFuture(
                                  new SessionProtocolNegotiationException(
                                          desiredProtocol, "previously failed negotiation")),
                          promise, timingsBuilder);
            return;
        }

        // Create a new connection.
        final Promise<Channel> sessionPromise = eventLoop.newPromise();
        connect(remoteAddress, desiredProtocol, key, sessionPromise);

        if (sessionPromise.isDone()) {
            notifyConnect(desiredProtocol, key, sessionPromise, promise, timingsBuilder);
        } else {
            sessionPromise.addListener((Future<Channel> future) -> {
                notifyConnect(desiredProtocol, key, future, promise, timingsBuilder);
            });
        }
    }

    /**
     * A low-level operation that triggers a new connection attempt. Used only by:
     * <ul>
     *   <li>{@link #connect(SessionProtocol, PoolKey, ChannelAcquisitionFuture,
     *       ClientConnectionTimingsBuilder)} - The pool has been exhausted.</li>
     *   <li>{@link HttpSessionHandler} - HTTP/2 upgrade has failed.</li>
     * </ul>
     */
    void connect(SocketAddress remoteAddress, SessionProtocol desiredProtocol,
                 PoolKey poolKey, Promise<Channel> sessionPromise) {

        final Bootstrap bootstrap = getBootstrap(desiredProtocol);

        bootstrap.register().addListener((ChannelFuture registerFuture) -> {
            if (!registerFuture.isSuccess()) {
                sessionPromise.tryFailure(registerFuture.cause());
                return;
            }

            final Channel channel = registerFuture.channel();
            configureProxy(channel, poolKey.proxyConfig, desiredProtocol);
            channel.connect(remoteAddress).addListener((ChannelFuture connectFuture) -> {
                if (connectFuture.isSuccess()) {
                    initSession(desiredProtocol, poolKey, connectFuture, sessionPromise);
                } else {
                    invokeProxyConnectFailed(desiredProtocol, poolKey, connectFuture.cause());
                    sessionPromise.tryFailure(connectFuture.cause());
                }
            });
        });
    }

    void invokeProxyConnectFailed(SessionProtocol protocol, PoolKey poolKey, Throwable cause) {
        try {
            final ProxyConfig proxyConfig = poolKey.proxyConfig;
            if (proxyConfig.proxyType() != ProxyType.DIRECT) {
                final InetSocketAddress proxyAddress = proxyConfig.proxyAddress();
                assert proxyAddress != null;
                final ProxyConfigSelector proxyConfigSelector = clientFactory.proxyConfigSelector();
                proxyConfigSelector.connectFailed(protocol, Endpoint.of(poolKey.host, poolKey.port),
                                                  proxyAddress, UnprocessedRequestException.of(cause));
            }
        } catch (Throwable t) {
            logger.warn("Exception while invoking {}.connectFailed() for {}",
                        ProxyConfigSelector.class.getSimpleName(), poolKey, t);
        }
    }

    private static InetSocketAddress toRemoteAddress(PoolKey key) throws UnknownHostException {
        final InetAddress inetAddr = InetAddress.getByAddress(
                key.host, NetUtil.createByteArrayFromIpAddressString(key.ipAddr));
        return new InetSocketAddress(inetAddr, key.port);
    }

    private void initSession(SessionProtocol desiredProtocol, PoolKey poolKey,
                             ChannelFuture connectFuture, Promise<Channel> sessionPromise) {
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

        ch.pipeline().addLast(
                new HttpSessionHandler(this, ch, sessionPromise, timeoutFuture,
                                       desiredProtocol, poolKey, clientFactory));
    }

    private void notifyConnect(SessionProtocol desiredProtocol, PoolKey key, Future<Channel> future,
                               ChannelAcquisitionFuture promise,
                               ClientConnectionTimingsBuilder timingsBuilder) {
        assert future.isDone();
        removePendingAcquisition(desiredProtocol, key);

        timingsBuilder.socketConnectEnd();
        try {
            if (future.isSuccess()) {
                final Channel channel = future.getNow();
                final SessionProtocol protocol = getProtocolIfHealthy(channel);
                if (protocol == null || closeable.isClosing()) {
                    channel.close();
                    promise.completeExceptionally(
                            UnprocessedRequestException.of(
                                    new ClosedSessionException("acquired an unhealthy connection")));
                    return;
                }

                allChannels.put(channel, Boolean.TRUE);

                try {
                    listener.connectionOpen(protocol,
                                            (InetSocketAddress) channel.remoteAddress(),
                                            (InetSocketAddress) channel.localAddress(),
                                            channel);
                } catch (Exception e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("{} Exception handling {}.connectionOpen()",
                                    channel, listener.getClass().getName(), e);
                    }
                }

                final HttpSession session = HttpSession.get(channel);
                if (session.incrementNumUnfinishedResponses()) {
                    if (protocol.isMultiplex()) {
                        final Http2PooledChannel pooledChannel = new Http2PooledChannel(channel, protocol);
                        addToPool(protocol, key, pooledChannel);
                        promise.complete(pooledChannel);
                    } else {
                        promise.complete(new Http1PooledChannel(channel, protocol, key));
                    }
                } else {
                    // Server set MAX_CONCURRENT_STREAMS to 0, which means we can't send anything.
                    channel.close();
                    promise.completeExceptionally(
                            UnprocessedRequestException.of(RefusedStreamException.get()));
                }

                channel.closeFuture().addListener(f -> {
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

                    try {
                        listener.connectionClosed(protocol,
                                                  (InetSocketAddress) channel.remoteAddress(),
                                                  (InetSocketAddress) channel.localAddress(),
                                                  channel);
                    } catch (Exception e) {
                        if (logger.isWarnEnabled()) {
                            logger.warn("{} Exception handling {}.connectionClosed()",
                                        channel, listener.getClass().getName(), e);
                        }
                    }
                });
            } else {
                final Throwable throwable = future.cause();
                if (throwable instanceof ProxyConnectException) {
                    invokeProxyConnectFailed(desiredProtocol, key, throwable);
                }
                promise.completeExceptionally(UnprocessedRequestException.of(throwable));
            }
        } catch (Exception e) {
            promise.completeExceptionally(UnprocessedRequestException.of(e));
        }
    }

    /**
     * Adds a {@link Channel} to this pool.
     */
    private void addToPool(SessionProtocol actualProtocol, PoolKey key, PooledChannel pooledChannel) {
        assert eventLoop.inEventLoop() : Thread.currentThread().getName();
        getOrCreatePool(actualProtocol, key).addLast(pooledChannel);
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return closeable.closeAsync();
    }

    private void closeAsync(CompletableFuture<?> future) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> closeAsync(future));
            return;
        }

        // NB: Make a copy first, because close() will trigger the closeFuture listener
        //     which mutates allChannels back, causing ConcurrentModificationException.
        final Channel[] allChannels = this.allChannels.keySet().toArray(EMPTY_CHANNELS);
        final int numAllChannels = allChannels.length;
        if (numAllChannels == 0) {
            future.complete(null);
            return;
        }

        // Complete the given future when all channels are closed.
        final ChannelFutureListener listener = new ChannelFutureListener() {
            private int numRemainingChannels = numAllChannels;

            @Override
            public void operationComplete(ChannelFuture unused) throws Exception {
                if (--numRemainingChannels <= 0) {
                    future.complete(null);
                }
            }
        };

        for (Channel ch : allChannels) {
            ch.close().addListener(listener);
        }
    }

    /**
     * Closes all {@link Channel}s managed by this pool.
     */
    @Override
    public void close() {
        if (Thread.currentThread() instanceof NonBlocking) {
            // Avoid blocking operation if we're in an event loop, because otherwise we might see a dead lock
            // while waiting for the channels to be closed.
            closeable.closeAsync();
        } else {
            closeable.close();
        }
    }

    static final class PoolKey {
        final String host;
        final String ipAddr;
        final int port;
        final int hashCode;
        final ProxyConfig proxyConfig;

        PoolKey(String host, String ipAddr, int port, ProxyConfig proxyConfig) {
            this.host = host;
            this.ipAddr = ipAddr;
            this.port = port;
            this.proxyConfig = proxyConfig;
            hashCode = ((host.hashCode() * 31 + ipAddr.hashCode()) * 31 + port) * 31 +
                       proxyConfig.hashCode();
        }

        @Override
        public boolean equals(@Nullable Object o) {
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
                   host.equals(that.host) &&
                   proxyConfig.equals(that.proxyConfig);
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
                              .add("proxyConfig", proxyConfig)
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
            if (!eventLoop.inEventLoop()) {
                eventLoop.execute(this::doRelease);
            } else {
                doRelease();
            }
        }

        private void doRelease() {
            if (isHealthy(this)) {
                // Channel turns out to be healthy. Add it back to the pool.
                addToPool(protocol(), key, this);
            } else {
                // Channel not healthy. Do not add it back to the pool.
            }
        }
    }

    /**
     * The result of piggybacked channel acquisition attempt.
     */
    private enum PiggybackedChannelAcquisitionResult {
        /**
         * Piggybacking succeeded. Use the channel from the current pending acquisition.
         */
        SUCCESS,
        /**
         * Piggybacking failed. Attempt to establish a new connection.
         */
        NEW_CONNECTION,
        /**
         * Piggybacking failed, but there's another pending acquisition.
         */
        PIGGYBACKED_AGAIN;
    }

    /**
     * A variant of {@link CompletableFuture} that keeps its completion handlers into a separate list.
     * This yields better performance than {@link CompletableFuture#handle(BiFunction)} as the number of
     * added handlers increases because it does not create a long linked list with extra wrappers, which is
     * especially beneficial for Java 8 which suffers a huge performance hit when complementing a future with
     * a deep stack. See {@code cleanStack()} in Java 8 {@link CompletableFuture} for more information.
     */
    private final class ChannelAcquisitionFuture extends CompletableFuture<PooledChannel> {

        /**
         * A {@code Consumer<PooledChannel>} if only 1 handler.
         * A {@code List<Consumer<PooledChannel>>} if there are 2+ handlers.
         */
        @Nullable
        private Object pendingPiggybackHandlers;

        void piggyback(SessionProtocol desiredProtocol, PoolKey key,
                       ChannelAcquisitionFuture childPromise,
                       ClientConnectionTimingsBuilder timingsBuilder) {

            // Add to the pending handler list if not complete yet.
            if (!isDone()) {
                final Consumer<PooledChannel> handler =
                        pch -> handlePiggyback(desiredProtocol, key, childPromise, timingsBuilder, pch);

                if (pendingPiggybackHandlers == null) {
                    // The 1st handler
                    pendingPiggybackHandlers = handler;
                    return;
                }

                if (!(pendingPiggybackHandlers instanceof List)) {
                    // The 2nd handler
                    @SuppressWarnings("unchecked")
                    final Consumer<PooledChannel> firstHandler =
                            (Consumer<PooledChannel>) pendingPiggybackHandlers;
                    final List<Consumer<PooledChannel>> list = new ArrayList<>();
                    list.add(firstHandler);
                    list.add(handler);
                    pendingPiggybackHandlers = list;
                    return;
                }

                // The 3rd or later handler
                @SuppressWarnings("unchecked")
                final List<Consumer<PooledChannel>> list =
                        (List<Consumer<PooledChannel>>) pendingPiggybackHandlers;
                list.add(handler);
                return;
            }

            // Handle immediately if complete already.
            handlePiggyback(desiredProtocol, key, childPromise, timingsBuilder,
                            isCompletedExceptionally() ? null : getNow(null));
        }

        private void handlePiggyback(SessionProtocol desiredProtocol, PoolKey key,
                                     ChannelAcquisitionFuture childPromise,
                                     ClientConnectionTimingsBuilder timingsBuilder,
                                     @Nullable PooledChannel pch) {

            final PiggybackedChannelAcquisitionResult result;
            if (pch != null) {
                final SessionProtocol actualProtocol = pch.protocol();
                if (actualProtocol.isMultiplex()) {
                    final HttpSession session = HttpSession.get(pch.get());
                    if (session.incrementNumUnfinishedResponses()) {
                        result = PiggybackedChannelAcquisitionResult.SUCCESS;
                    } else if (usePendingAcquisition(actualProtocol, key, childPromise, timingsBuilder)) {
                        result = PiggybackedChannelAcquisitionResult.PIGGYBACKED_AGAIN;
                    } else {
                        result = PiggybackedChannelAcquisitionResult.NEW_CONNECTION;
                    }
                } else {
                    // Try to acquire again because the connection was not HTTP/2.
                    // We use the exact protocol (H1 or H1C) instead of 'desiredProtocol' so that
                    // we do not waste our time looking for pending acquisitions for the host
                    // that does not support HTTP/2.
                    final PooledChannel ch = acquireNow(actualProtocol, key);
                    if (ch != null) {
                        pch = ch;
                        result = PiggybackedChannelAcquisitionResult.SUCCESS;
                    } else {
                        result = PiggybackedChannelAcquisitionResult.NEW_CONNECTION;
                    }
                }
            } else {
                result = PiggybackedChannelAcquisitionResult.NEW_CONNECTION;
            }

            switch (result) {
                case SUCCESS:
                    timingsBuilder.pendingAcquisitionEnd();
                    childPromise.complete(pch);
                    break;
                case NEW_CONNECTION:
                    timingsBuilder.pendingAcquisitionEnd();
                    connect(desiredProtocol, key, childPromise, timingsBuilder);
                    break;
                case PIGGYBACKED_AGAIN:
                    // There's nothing to do because usePendingAcquisition() was called successfully above.
                    break;
            }
        }

        @Override
        public boolean complete(PooledChannel value) {
            assert value != null;
            if (!super.complete(value)) {
                return false;
            }

            handlePendingPiggybacks(value);
            return true;
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            if (!super.completeExceptionally(ex)) {
                return false;
            }

            handlePendingPiggybacks(null);
            return true;
        }

        private void handlePendingPiggybacks(@Nullable PooledChannel value) {
            final Object pendingPiggybackHandlers = this.pendingPiggybackHandlers;
            if (pendingPiggybackHandlers == null) {
                return;
            }

            this.pendingPiggybackHandlers = null;

            if (!(pendingPiggybackHandlers instanceof List)) {
                // 1 handler
                @SuppressWarnings("unchecked")
                final Consumer<PooledChannel> handler =
                        (Consumer<PooledChannel>) pendingPiggybackHandlers;
                handler.accept(value);
                return;
            }

            // 2+ handlers
            @SuppressWarnings("unchecked")
            final List<Consumer<PooledChannel>> list =
                    (List<Consumer<PooledChannel>>) pendingPiggybackHandlers;
            for (Consumer<PooledChannel> handler : list) {
                handler.accept(value);
            }
        }
    }
}
