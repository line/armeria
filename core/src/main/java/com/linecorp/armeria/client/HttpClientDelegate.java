/*
 * Copyright 2016 LINE Corporation
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

import static com.linecorp.armeria.internal.ArmeriaHttpUtil.splitPathAndQuery;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.pool.DefaultKeyedChannelPool;
import com.linecorp.armeria.client.pool.KeyedChannelPool;
import com.linecorp.armeria.client.pool.KeyedChannelPoolHandler;
import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

final class HttpClientDelegate implements Client<HttpRequest, HttpResponse> {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientDelegate.class);

    private static final ChannelHealthChecker POOL_HEALTH_CHECKER =
            ch -> ch.eventLoop().newSucceededFuture(ch.isActive() && HttpSession.get(ch).isActive());

    final ConcurrentMap<EventLoop, KeyedChannelPool<PoolKey>> map = new ConcurrentHashMap<>();

    private final HttpClientFactory factory;

    HttpClientDelegate(HttpClientFactory factory) {
        this.factory = requireNonNull(factory, "factory");
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final Endpoint endpoint = ctx.endpoint().resolve().withDefaultPort(ctx.sessionProtocol().defaultPort());
        autoFillHeaders(ctx, endpoint, req);
        if (!sanitizePath(req)) {
            req.abort();
            return HttpResponse.ofFailure(new IllegalArgumentException("invalid path: " + req.path()));
        }

        final PoolKey poolKey = new PoolKey(
                InetSocketAddress.createUnresolved(endpoint.host(), endpoint.port()),
                ctx.sessionProtocol());

        final EventLoop eventLoop = ctx.eventLoop();
        final Future<Channel> channelFuture = pool(eventLoop).acquire(poolKey);
        final DecodedHttpResponse res = new DecodedHttpResponse(eventLoop);

        if (channelFuture.isDone()) {
            if (channelFuture.isSuccess()) {
                Channel ch = channelFuture.getNow();
                invoke0(ch, ctx, req, res, poolKey);
            } else {
                res.close(channelFuture.cause());
            }
        } else {
            channelFuture.addListener((Future<Channel> future) -> {
                if (future.isSuccess()) {
                    Channel ch = future.getNow();
                    invoke0(ch, ctx, req, res, poolKey);
                } else {
                    res.close(channelFuture.cause());
                }
            });
        }

        return res;
    }

    private static void autoFillHeaders(ClientRequestContext ctx, Endpoint endpoint, HttpRequest req) {
        requireNonNull(req, "req");
        final HttpHeaders headers = req.headers();

        if (headers.authority() == null) {
            final String hostname = endpoint.host();
            final int port = endpoint.port();

            final String authority;
            if (port == ctx.sessionProtocol().defaultPort()) {
                authority = hostname;
            } else {
                final StringBuilder buf = new StringBuilder(hostname.length() + 6);
                buf.append(hostname);
                buf.append(':');
                buf.append(port);
                authority = buf.toString();
            }

            headers.authority(authority);
        }

        if (headers.scheme() == null) {
            headers.scheme(ctx.sessionProtocol().isTls() ? "https" : "http");
        }

        // Add the headers specified in ClientOptions, if not overridden by request.
        if (ctx.hasAttr(ClientRequestContext.HTTP_HEADERS)) {
            HttpHeaders clientOptionHeaders = ctx.attr(ClientRequestContext.HTTP_HEADERS).get();
            clientOptionHeaders.forEach(entry -> {
                AsciiString name = entry.getKey();
                if (!headers.contains(name)) {
                    headers.set(name, entry.getValue());
                }
            });
        }

        if (!headers.contains(HttpHeaderNames.USER_AGENT)) {
            headers.set(HttpHeaderNames.USER_AGENT, HttpHeaderUtil.USER_AGENT.toString());
        }
    }

    private static boolean sanitizePath(HttpRequest req) {
        final String[] pathAndQuery = splitPathAndQuery(req.path());
        if (pathAndQuery == null) {
            return false;
        }

        final String path = pathAndQuery[0];
        final String query = pathAndQuery[1];
        final String newPathAndQuery;
        if (query != null) {
            newPathAndQuery = path + '?' + query;
        } else {
            newPathAndQuery = path;
        }

        req.path(newPathAndQuery);
        return true;
    }

    private KeyedChannelPool<PoolKey> pool(EventLoop eventLoop) {
        KeyedChannelPool<PoolKey> pool = map.get(eventLoop);
        if (pool != null) {
            return pool;
        }

        return map.computeIfAbsent(eventLoop, e -> {
            Function<PoolKey, Future<Channel>> channelFactory =
                    new HttpSessionChannelFactory(factory, eventLoop);

            @SuppressWarnings("unchecked")
            final KeyedChannelPoolHandler<PoolKey> handler =
                    (KeyedChannelPoolHandler<PoolKey>) factory.connectionPoolListener();

            final KeyedChannelPool<PoolKey> newPool = new DefaultKeyedChannelPool<>(
                    eventLoop, channelFactory, POOL_HEALTH_CHECKER, handler, true);

            eventLoop.terminationFuture().addListener((FutureListener<Object>) f -> {
                map.remove(eventLoop);
                newPool.close();
            });

            return newPool;
        });
    }

    void invoke0(Channel channel, ClientRequestContext ctx,
                 HttpRequest req, DecodedHttpResponse res, PoolKey poolKey) {
        final KeyedChannelPool<PoolKey> pool = KeyedChannelPool.findPool(channel);
        boolean needsRelease = true;
        try {
            final HttpSession session = HttpSession.get(channel);
            res.init(session.inboundTrafficController());
            final SessionProtocol sessionProtocol = session.protocol();
            if (sessionProtocol == null) {
                needsRelease = false;
                try {
                    res.close(ClosedSessionException.get());
                } finally {
                    channel.close();
                }
                return;
            }

            if (session.invoke(ctx, req, res)) {
                needsRelease = false;

                // Return the channel to the pool.
                if (sessionProtocol.isMultiplex()) {
                    release(pool, poolKey, channel);
                } else {
                    // If pipelining is enabled, return as soon as the request is fully sent.
                    // If pipelining is disabled, return after the response is fully received.
                    final CompletableFuture<Void> closeFuture =
                            factory.useHttp1Pipelining() ? req.closeFuture() : res.closeFuture();
                    closeFuture.whenComplete((ret, cause) -> release(pool, poolKey, channel));
                }
            }
        } finally {
            if (needsRelease) {
                release(pool, poolKey, channel);
            }
        }
    }

    private static void release(KeyedChannelPool<PoolKey> pool, PoolKey poolKey, Channel channel) {
        try {
            pool.release(poolKey, channel);
        } catch (Throwable t) {
            logger.warn("Failed to return a Channel to the pool: {}", channel, t);
        }
    }

    void close() {
        map.values().forEach(KeyedChannelPool::close);
    }
}
