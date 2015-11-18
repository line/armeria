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
package com.linecorp.armeria.client;

import static com.linecorp.armeria.common.SessionProtocol.H1;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2;
import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientCodec.EncodeResult;
import com.linecorp.armeria.client.HttpSessionHandler.Invocation;
import com.linecorp.armeria.client.pool.KeyedChannelPool;
import com.linecorp.armeria.client.pool.KeyedChannelPoolHandler;
import com.linecorp.armeria.client.pool.KeyedChannelPoolHandlerAdapter;
import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.client.pool.DefaultKeyedChannelPool;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.OneTimeTask;
import io.netty.util.internal.PlatformDependent;

final class HttpRemoteInvoker implements RemoteInvoker {

    private static final KeyedChannelPoolHandlerAdapter<PoolKey> NOOP_POOL_HANDLER =
            new KeyedChannelPoolHandlerAdapter<>();

    static final Set<SessionProtocol> HTTP_PROTOCOLS = EnumSet.of(H1, H1C, H2, H2C, HTTPS, HTTP);

    final ConcurrentMap<EventLoop, KeyedChannelPool<PoolKey>> map = PlatformDependent.newConcurrentHashMap();

    private final EventLoopGroup eventLoopGroup;
    private final Bootstrap baseBootstrap;
    private final RemoteInvokerOptions options;

    HttpRemoteInvoker(EventLoopGroup eventLoopGroup, Bootstrap baseBootstrap, RemoteInvokerOptions options) {
        this.eventLoopGroup = requireNonNull(eventLoopGroup, "eventLoopGroup");
        this.baseBootstrap = requireNonNull(baseBootstrap, "baseBootstrap");
        this.options = requireNonNull(options, "options");

        assert baseBootstrap.group() == null;
    }

    private KeyedChannelPool<PoolKey> pool(EventLoop eventLoop) {
        KeyedChannelPool<PoolKey> pool = map.get(eventLoop);
        if (pool != null) {
            return pool;
        }

        return map.computeIfAbsent(eventLoop, e -> {
            final Bootstrap bootstrap = baseBootstrap.clone();
            bootstrap.group(eventLoop);

            Function<PoolKey, Future<Channel>> factory = new HttpSessionChannelFactory(bootstrap, options);

            final KeyedChannelPoolHandler<PoolKey> handler =
                    options.poolHandlerDecorator().apply(NOOP_POOL_HANDLER);

            eventLoop.terminationFuture().addListener((FutureListener<Object>) f -> map.remove(eventLoop));

            //TODO(inch772) handle options.maxConcurrency();
            return new DefaultKeyedChannelPool<>(eventLoop, factory,
                                                 HttpSessionChannelFactory.HEALTH_CHECKER, handler, true);
        });
    }

    static PoolKey poolKey(InetSocketAddress remoteAddress, SessionProtocol sessionProtocol) {
        return new PoolKey(remoteAddress, sessionProtocol);
    }

    @Override
    public <T> Future<T> invoke(URI uri, ClientOptions options, ClientCodec codec, Method method,
                                Object[] args) throws Exception {
        requireNonNull(uri, "uri");
        requireNonNull(options, "options");
        requireNonNull(codec, "codec");
        requireNonNull(method, "method");

        final EventLoop eventLoop = eventLoop();
        final Scheme scheme = Scheme.parse(uri.getScheme());
        final SessionProtocol sessionProtocol = validateSessionProtocol(scheme.sessionProtocol());
        final InetSocketAddress remoteAddress = convertToSocketAddress(uri, sessionProtocol.isTls());

        PoolKey poolKey = poolKey(remoteAddress, sessionProtocol);
        final Future<Channel> channelFuture = pool(eventLoop).acquire(poolKey);

        final Promise<T> resultPromise = eventLoop.newPromise();

        codec.prepareRequest(method, args, resultPromise);
        if (channelFuture.isSuccess()) {
            Channel ch = channelFuture.getNow();
            invoke0(sessionProtocol, codec, ch, method, args, options, resultPromise, poolKey);
        } else {
            channelFuture.addListener((Future<Channel> future) -> {
                if (future.isSuccess()) {
                    Channel ch = future.getNow();
                    invoke0(sessionProtocol, codec, ch, method, args, options, resultPromise, poolKey);
                } else {
                    resultPromise.setFailure(channelFuture.cause());
                }
            });
        }

        return resultPromise;
    }

    private EventLoop eventLoop() {
        final ServiceInvocationContext currentServerContext = ServiceInvocationContext.current().orElse(null);
        if (currentServerContext != null) {
            // Use the current server-side event loop if possible.
            return currentServerContext.eventLoop();
        } else {
            return eventLoopGroup.next();
        }
    }

    static <T> void invoke0(SessionProtocol sessionProtocol, ClientCodec codec, Channel channel,
                            Method method, Object[] args, ClientOptions options,
                            Promise<T> resultPromise, PoolKey poolKey) {

        EncodeResult encodeResult = codec.encodeRequest(channel, method, args);
        if (encodeResult.isSuccess()) {
            ServiceInvocationContext ctx = encodeResult.invocationContext();
            Promise<FullHttpResponse> responsePromise = channel.eventLoop().newPromise();

            final Invocation invocation = new Invocation(ctx, options, responsePromise, encodeResult.content());
            //write request
            final ChannelFuture writeFuture = writeRequest(channel, invocation, ctx, options);
            writeFuture.addListener(fut -> {
                if (!fut.isSuccess()) {
                    responsePromise.tryFailure(fut.cause());
                } else {
                    long responseTimeoutMillis = options.responseTimeoutPolicy().timeout(ctx);
                    scheduleTimeout(channel, responsePromise, responseTimeoutMillis);
                }
            });

            //handle response
            if (responsePromise.isSuccess()) {
                decodeResult(codec, resultPromise, ctx, responsePromise.getNow());
            } else {
                responsePromise.addListener((Future<FullHttpResponse> future) -> {
                    if (future.isSuccess()) {
                        decodeResult(codec, resultPromise, ctx, responsePromise.getNow());
                    } else {
                        resultPromise.tryFailure(future.cause());
                    }
                });
            }
        } else {
            resultPromise.setFailure(encodeResult.cause());
        }

        //release channel
        final KeyedChannelPool<PoolKey> pool = KeyedChannelPool.findPool(channel);
        if (sessionProtocol.isMultiplex()) {
            pool.release(poolKey, channel);
        } else {
            resultPromise.addListener(fut -> pool.release(poolKey, channel));
        }
    }

    private static <T> void decodeResult(ClientCodec codec, Promise<T> resultPromise,
                                         ServiceInvocationContext ctx, FullHttpResponse response) {
        try {
            resultPromise.setSuccess(codec.decodeResponse(ctx, response.content(), response));
        } catch (Throwable e) {
            resultPromise.tryFailure(e);
        } finally {
            ReferenceCountUtil.release(response);
        }
    }

    private static ChannelFuture writeRequest(Channel channel, Invocation invocation,
                                              ServiceInvocationContext ctx, ClientOptions options) {
        final long writeTimeoutMillis = options.writeTimeoutPolicy().timeout(ctx);
        final ChannelPromise writePromise = channel.newPromise();
        channel.writeAndFlush(invocation, writePromise);
        scheduleTimeout(channel, writePromise, writeTimeoutMillis);
        return writePromise;
    }

    private static <T> void scheduleTimeout(Channel channel, Promise<T> promise, long timeoutMillis) {
        final ScheduledFuture<?> timeoutFuture;
        if (timeoutMillis > 0) {
            timeoutFuture = channel.eventLoop().schedule(
                    new TimeoutTask(promise, timeoutMillis),
                    timeoutMillis, TimeUnit.MILLISECONDS);
        } else {
            timeoutFuture = null;
        }

        promise.addListener(future -> {
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
        });
    }

    private static class TimeoutTask extends OneTimeTask {

        private final Promise<?> promise;
        private final long timeoutMillis;

        private TimeoutTask(Promise<?> promise, long timeoutMillis) {
            this.promise = promise;
            this.timeoutMillis = timeoutMillis;
        }

        @Override
        public void run() {
            promise.tryFailure(new WriteTimeoutException(
                    "write timed out within " + timeoutMillis + "ms"));
        }
    }

    private static InetSocketAddress convertToSocketAddress(URI uri, boolean useTls) {
        int port = uri.getPort();
        if (port < 0) {
            port = useTls ? 443 : 80;
        }
        return InetSocketAddress.createUnresolved(uri.getHost(), port);
    }

    private static SessionProtocol validateSessionProtocol(SessionProtocol sessionProtocol) {
        requireNonNull(sessionProtocol);
        if (!HTTP_PROTOCOLS.contains(sessionProtocol)) {
            throw new IllegalArgumentException(
                    "unsupported session protocol: " + sessionProtocol);
        }
        return sessionProtocol;
    }

    @Override
    public void close() {
        map.values().forEach(KeyedChannelPool<PoolKey>::close);
    }
}
