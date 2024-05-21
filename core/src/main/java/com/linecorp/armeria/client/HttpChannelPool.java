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

import static com.linecorp.armeria.common.SessionProtocol.httpAndHttpsValues;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.toNettyHttp1ClientHeaders;

import java.lang.reflect.Array;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.proxy.ConnectProxyConfig;
import com.linecorp.armeria.client.proxy.HAProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyConfigSelector;
import com.linecorp.armeria.client.proxy.ProxyType;
import com.linecorp.armeria.client.proxy.Socks4ProxyConfig;
import com.linecorp.armeria.client.proxy.Socks5ProxyConfig;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ClientConnectionTimingsBuilder;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.internal.client.HttpSession;
import com.linecorp.armeria.internal.client.PooledChannel;
import com.linecorp.armeria.internal.common.util.ChannelUtil;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import reactor.core.scheduler.NonBlocking;

final class HttpChannelPool implements AsyncCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HttpChannelPool.class);
    private static final Channel[] EMPTY_CHANNELS = new Channel[0];

    static final AttributeKey<ClientConnectionTimingsBuilder> TIMINGS_BUILDER_KEY =
            AttributeKey.valueOf(HttpChannelPool.class, "TIMINGS_BUILDER_KEY");

    private final HttpClientFactory clientFactory;
    private final EventLoop eventLoop;
    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync);

    // Fields for pooling connections:
    private final Map<PoolKey, Deque<PooledChannel>>[] pool;
    private final Map<PoolKey, ChannelAcquisitionFuture>[] pendingAcquisitions;
    private final Map<Channel, Boolean> allChannels;
    private final ConnectionPoolListener listener;

    // Fields for creating a new connection:
    private final Bootstraps bootstraps;
    private final int connectTimeoutMillis;

    HttpChannelPool(HttpClientFactory clientFactory, EventLoop eventLoop,
                    SslContext sslCtxHttp1Or2, SslContext sslCtxHttp1Only,
                    ConnectionPoolListener listener) {
        this.clientFactory = clientFactory;
        this.eventLoop = eventLoop;
        this.listener = listener;

        pool = newEnumMap(ImmutableSet.of(SessionProtocol.H1, SessionProtocol.H1C,
                                          SessionProtocol.H2, SessionProtocol.H2C));
        pendingAcquisitions = newEnumMap(httpAndHttpsValues());
        allChannels = new IdentityHashMap<>();
        connectTimeoutMillis = (Integer) clientFactory.options()
                .channelOptions()
                .get(ChannelOption.CONNECT_TIMEOUT_MILLIS);
        bootstraps = new Bootstraps(clientFactory, eventLoop, sslCtxHttp1Or2, sslCtxHttp1Only);
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
                final HttpHeaders proxyHeaders = toNettyHttp1ClientHeaders(connectProxyConfig.headers());
                if (username == null || password == null) {
                    proxyHandler = new HttpProxyHandler(proxyAddress, proxyHeaders);
                } else {
                    proxyHandler = new HttpProxyHandler(proxyAddress, username, password, proxyHeaders);
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
            final SslContext sslCtx = bootstraps.determineSslContext(desiredProtocol);
            ch.pipeline().addFirst(sslCtx.newHandler(ch.alloc()));
        }
    }

    /**
     * Returns an array whose index signifies {@link SessionProtocol#ordinal()}. Similar to {@link EnumMap}.
     */
    private static <T> Map<PoolKey, T>[] newEnumMap(Set<SessionProtocol> allowedProtocols) {
        @SuppressWarnings("unchecked")
        final Map<PoolKey, T>[] maps =
                (Map<PoolKey, T>[]) Array.newInstance(Map.class, SessionProtocol.values().length);
        // Attempting to access the array with an unallowed protocol will trigger NPE,
        // which will help us find a bug.
        for (SessionProtocol p : allowedProtocols) {
            maps[p.ordinal()] = new HashMap<>();
        }
        return maps;
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
        assert !desiredProtocol.isExplicitHttp1() : "desiredProtocol: " + desiredProtocol;
        final ChannelAcquisitionFuture future = pendingAcquisitions[desiredProtocol.ordinal()].get(key);
        if (future == null) {
            // Try to find a pending acquisition from the explicit protocols.
            switch (desiredProtocol) {
                case HTTP:
                    return pendingAcquisitions[SessionProtocol.H2C.ordinal()].get(key);
                case HTTPS:
                    return pendingAcquisitions[SessionProtocol.H2.ordinal()].get(key);
            }
        }
        return future;
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
     *         requested via {@link #acquireLater(SessionProtocol, SerializationFormat,
     *         PoolKey, ClientConnectionTimingsBuilder)}.
     */
    @Nullable
    @SuppressWarnings("checkstyle:FallThrough")
    PooledChannel acquireNow(SessionProtocol desiredProtocol, SerializationFormat serializationFormat,
                             PoolKey key) {
        PooledChannel ch;
        switch (desiredProtocol) {
            case HTTP:
                ch = acquireNowExact(key, SessionProtocol.H2C, serializationFormat);
                if (ch == null) {
                    ch = acquireNowExact(key, SessionProtocol.H1C, serializationFormat);
                }
                break;
            case HTTPS:
                ch = acquireNowExact(key, SessionProtocol.H2, serializationFormat);
                if (ch == null) {
                    ch = acquireNowExact(key, SessionProtocol.H1, serializationFormat);
                }
                break;
            default:
                ch = acquireNowExact(key, desiredProtocol, serializationFormat);
        }
        return ch;
    }

    @Nullable
    private PooledChannel acquireNowExact(PoolKey key, SessionProtocol protocol,
                                          SerializationFormat serializationFormat) {
        if (serializationFormat.requiresNewConnection(protocol)) {
            return null;
        }
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
        return ch.isActive() && HttpSession.get(ch).isAcquirable();
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
    CompletableFuture<PooledChannel> acquireLater(SessionProtocol desiredProtocol,
                                                  SerializationFormat serializationFormat,
                                                  PoolKey key,
                                                  ClientConnectionTimingsBuilder timingsBuilder) {
        final ChannelAcquisitionFuture promise = new ChannelAcquisitionFuture();
        if (!usePendingAcquisition(desiredProtocol, serializationFormat, key, promise, timingsBuilder)) {
            connect(desiredProtocol, serializationFormat, key, promise, timingsBuilder);
        }
        return promise;
    }

    /**
     * Tries to use the pending HTTP/2 connection to avoid creating an extra connection.
     *
     * @return {@code true} if succeeded to reuse the pending connection.
     */
    private boolean usePendingAcquisition(SessionProtocol desiredProtocol,
                                          SerializationFormat serializationFormat,
                                          PoolKey key,
                                          ChannelAcquisitionFuture promise,
                                          ClientConnectionTimingsBuilder timingsBuilder) {

        if (desiredProtocol.isExplicitHttp1()) {
            // Can't use HTTP/1 connections because they will not be available in the pool until
            // the request is done.
            return false;
        }

        final ChannelAcquisitionFuture pendingAcquisition = getPendingAcquisition(desiredProtocol, key);
        if (pendingAcquisition == null) {
            return false;
        }

        timingsBuilder.pendingAcquisitionStart();
        pendingAcquisition.piggyback(desiredProtocol, serializationFormat, key, promise, timingsBuilder);
        return true;
    }

    private void connect(SessionProtocol desiredProtocol, SerializationFormat serializationFormat,
                         PoolKey key, ChannelAcquisitionFuture promise,
                         ClientConnectionTimingsBuilder timingsBuilder) {
        setPendingAcquisition(desiredProtocol, key, promise);
        timingsBuilder.socketConnectStart();

        // Fail immediately if it is certain that the remote address doesn't support the desired protocol.
        final SocketAddress remoteAddress = key.toRemoteAddress();
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
        connect(remoteAddress, desiredProtocol, serializationFormat, key, sessionPromise, timingsBuilder);

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
     *   <li>{@link #connect(SessionProtocol, SerializationFormat, PoolKey, ChannelAcquisitionFuture,
     *                       ClientConnectionTimingsBuilder)} - The pool has been exhausted.</li>
     *   <li>{@link HttpSessionHandler} - HTTP/2 upgrade has failed.</li>
     * </ul>
     */
    void connect(SocketAddress remoteAddress, SessionProtocol desiredProtocol,
                 SerializationFormat serializationFormat,
                 PoolKey poolKey, Promise<Channel> sessionPromise,
                 @Nullable ClientConnectionTimingsBuilder timingsBuilder) {
        final Bootstrap bootstrap;
        try {
            bootstrap = bootstraps.get(remoteAddress, desiredProtocol, serializationFormat);
        } catch (Exception e) {
            sessionPromise.tryFailure(e);
            return;
        }

        bootstrap.register().addListener((ChannelFuture registerFuture) -> {
            if (!registerFuture.isSuccess()) {
                sessionPromise.tryFailure(registerFuture.cause());
                return;
            }

            try {
                final Channel channel = registerFuture.channel();
                configureProxy(channel, poolKey.proxyConfig, desiredProtocol);

                if (desiredProtocol.isTls() && timingsBuilder != null) {
                    channel.attr(TIMINGS_BUILDER_KEY).set(timingsBuilder);
                }

                // should be invoked right before channel.connect() is invoked as defined in javadocs
                clientFactory.channelPipelineCustomizer().accept(channel.pipeline());

                final ChannelPromise connectionPromise = channel.newPromise();
                // Add a listener in advance so that the HTTP/1 pipeline can deliver the SessionProtocol event
                // to HttpSessionHandler.
                connectionPromise.addListener((ChannelFuture connectFuture) -> {
                    if (connectFuture.isSuccess()) {
                        initSession(desiredProtocol, serializationFormat,
                                    poolKey, connectFuture, sessionPromise);
                    } else {
                        maybeHandleProxyFailure(desiredProtocol, poolKey, connectFuture.cause());
                        sessionPromise.tryFailure(connectFuture.cause());
                    }
                });
                channel.connect(remoteAddress, connectionPromise);
            } catch (Throwable cause) {
                maybeHandleProxyFailure(desiredProtocol, poolKey, cause);
                sessionPromise.tryFailure(cause);
            }
        });
    }

    /**
     * Returns the number of open connections on this {@link HttpChannelPool}.
     */
    int numConnections() {
        return allChannels.size();
    }

    void maybeHandleProxyFailure(SessionProtocol protocol, PoolKey poolKey, Throwable cause) {
        try {
            final ProxyConfig proxyConfig = poolKey.proxyConfig;
            if (proxyConfig.proxyType() != ProxyType.DIRECT) {
                final InetSocketAddress proxyAddress = proxyConfig.proxyAddress();
                assert proxyAddress != null;
                final ProxyConfigSelector proxyConfigSelector = clientFactory.proxyConfigSelector();
                proxyConfigSelector.connectFailed(protocol, poolKey.endpoint,
                                                  proxyAddress, UnprocessedRequestException.of(cause));
            }
        } catch (Throwable t) {
            logger.warn("Exception while invoking {}.connectFailed() for {}",
                        ProxyConfigSelector.class.getSimpleName(), poolKey, t);
        }
    }

    private void initSession(SessionProtocol desiredProtocol, SerializationFormat serializationFormat,
                             PoolKey poolKey, ChannelFuture connectFuture, Promise<Channel> sessionPromise) {
        assert connectFuture.isSuccess();

        final Channel ch = connectFuture.channel();
        final EventLoop eventLoop = ch.eventLoop();
        assert eventLoop.inEventLoop();

        ch.pipeline().addLast(
                new HttpSessionHandler(this, ch, sessionPromise, connectTimeoutMillis,
                                       desiredProtocol, serializationFormat, poolKey, clientFactory));
    }

    private void notifyConnect(SessionProtocol desiredProtocol,
                               PoolKey key, Future<Channel> future,
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

                final InetSocketAddress remoteAddr = ChannelUtil.remoteAddress(channel);
                final InetSocketAddress localAddr = ChannelUtil.localAddress(channel);
                assert remoteAddr != null && localAddr != null
                        : "raddr: " + remoteAddr + ", laddr: " + localAddr;
                try {
                    listener.connectionOpen(protocol, remoteAddr, localAddr, channel);
                } catch (Throwable e) {
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
                        listener.connectionClosed(protocol, remoteAddr, localAddr, channel);
                    } catch (Throwable e) {
                        if (logger.isWarnEnabled()) {
                            logger.warn("{} Exception handling {}.connectionClosed()",
                                        channel, listener.getClass().getName(), e);
                        }
                    }
                });
            } else {
                final Throwable throwable = future.cause();
                if (throwable instanceof ProxyConnectException) {
                    maybeHandleProxyFailure(desiredProtocol, key, throwable);
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
        final Endpoint endpoint;
        final ProxyConfig proxyConfig;
        private final int hashCode;

        PoolKey(Endpoint endpoint, ProxyConfig proxyConfig) {
            this.endpoint = endpoint;
            this.proxyConfig = proxyConfig;
            hashCode = endpoint.hashCode() * 31 + proxyConfig.hashCode();
        }

        SocketAddress toRemoteAddress() {
            final InetSocketAddress remoteAddr = endpoint.toSocketAddress(-1);
            if (endpoint.isDomainSocket()) {
                return ((com.linecorp.armeria.common.util.DomainSocketAddress) remoteAddr).asNettyAddress();
            }

            assert !remoteAddr.isUnresolved() || proxyConfig.proxyType().isForwardProxy()
                    : remoteAddr + ", " + proxyConfig;

            return remoteAddr;
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
            return hashCode == that.hashCode &&
                   endpoint.equals(that.endpoint) &&
                   proxyConfig.equals(that.proxyConfig);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            final String host = endpoint.host();
            final String ipAddr = endpoint.ipAddr();
            final int port = endpoint.port();
            final boolean isDomainSocket = endpoint.isDomainSocket();
            final String proxyConfigStr = proxyConfig.proxyType() != ProxyType.DIRECT ? proxyConfig.toString()
                                                                                      : null;
            try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
                final StringBuilder buf = tempThreadLocals.stringBuilder();
                buf.append('{').append(host);
                if (!isDomainSocket) {
                    if (ipAddr != null) {
                        buf.append('/').append(ipAddr);
                    }
                    buf.append(':').append(port);
                }
                if (proxyConfigStr != null) {
                    buf.append(" via ");
                    buf.append(proxyConfigStr);
                }
                buf.append('}');
                return buf.toString();
            }
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
        PIGGYBACKED_AGAIN
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

        void piggyback(SessionProtocol desiredProtocol, SerializationFormat serializationFormat, PoolKey key,
                       ChannelAcquisitionFuture childPromise,
                       ClientConnectionTimingsBuilder timingsBuilder) {

            // Add to the pending handler list if not complete yet.
            if (!isDone()) {
                final Consumer<PooledChannel> handler =
                        pch -> handlePiggyback(desiredProtocol, serializationFormat, key,
                                               childPromise, timingsBuilder, pch);

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
            handlePiggyback(desiredProtocol, serializationFormat, key, childPromise, timingsBuilder,
                            isCompletedExceptionally() ? null : getNow(null));
        }

        private void handlePiggyback(SessionProtocol desiredProtocol,
                                     SerializationFormat serializationFormat,
                                     PoolKey key,
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
                        // Should use the same protocol used to acquire a new connection.
                    } else if (usePendingAcquisition(desiredProtocol, serializationFormat,
                                                     key, childPromise, timingsBuilder)) {
                        result = PiggybackedChannelAcquisitionResult.PIGGYBACKED_AGAIN;
                    } else {
                        result = PiggybackedChannelAcquisitionResult.NEW_CONNECTION;
                    }
                } else {
                    // Try to acquire again because the connection was not HTTP/2.
                    // We use the exact protocol (H1 or H1C) instead of 'desiredProtocol' so that
                    // we do not waste our time looking for pending acquisitions for the host
                    // that does not support HTTP/2.
                    final PooledChannel ch = acquireNow(actualProtocol, serializationFormat, key);
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
                    connect(desiredProtocol, serializationFormat, key, childPromise, timingsBuilder);
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
