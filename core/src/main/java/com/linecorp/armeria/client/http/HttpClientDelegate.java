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
package com.linecorp.armeria.client.http;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.SessionOptions;
import com.linecorp.armeria.client.pool.DefaultKeyedChannelPool;
import com.linecorp.armeria.client.pool.KeyedChannelPool;
import com.linecorp.armeria.client.pool.KeyedChannelPoolHandler;
import com.linecorp.armeria.client.pool.KeyedChannelPoolHandlerAdapter;
import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.util.CompletionActions;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

final class HttpClientDelegate implements Client<HttpRequest, HttpResponse> {

    private static final KeyedChannelPoolHandlerAdapter<PoolKey> NOOP_POOL_HANDLER =
            new KeyedChannelPoolHandlerAdapter<>();

    private static final ChannelHealthChecker POOL_HEALTH_CHECKER =
            ch -> ch.eventLoop().newSucceededFuture(HttpSession.get(ch).isActive());

    private static final Pattern CONSECUTIVE_SLASHES_PATTERN = Pattern.compile("/{2,}");

    final ConcurrentMap<EventLoop, KeyedChannelPool<PoolKey>> map = new ConcurrentHashMap<>();

    private final HttpClientFactory factory;

    HttpClientDelegate(HttpClientFactory factory) {
        this.factory = requireNonNull(factory, "factory");
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final Endpoint endpoint = ctx.endpoint().resolve().withDefaultPort(ctx.sessionProtocol().defaultPort());
        autoFillHeaders(ctx, endpoint, req);
        sanitizePath(req);

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

    private static void sanitizePath(HttpRequest req) {
        final String path = req.path();
        if (path == null || path.isEmpty()) {
            req.path("/");
            return;
        }

        // Remove consecutive slashes from the path.
        final int queryStart = path.indexOf('?');
        if (queryStart < 0) {
            final String newPath = CONSECUTIVE_SLASHES_PATTERN.matcher(path).replaceAll("/");
            if (newPath != path) {
                req.path(newPath);
            }
        } else {
            req.path(CONSECUTIVE_SLASHES_PATTERN.matcher(path.substring(0, queryStart)).replaceAll("/") +
                     path.substring(queryStart));
        }
    }

    private KeyedChannelPool<PoolKey> pool(EventLoop eventLoop) {
        KeyedChannelPool<PoolKey> pool = map.get(eventLoop);
        if (pool != null) {
            return pool;
        }

        return map.computeIfAbsent(eventLoop, e -> {
            final Bootstrap bootstrap = factory.newBootstrap();
            final SessionOptions options = factory.options();

            bootstrap.group(eventLoop);

            Function<PoolKey, Future<Channel>> channelFactory =
                    new HttpSessionChannelFactory(bootstrap, options);

            final KeyedChannelPoolHandler<PoolKey> handler =
                    options.poolHandlerDecorator().apply(NOOP_POOL_HANDLER);

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

        final HttpSession session = HttpSession.get(channel);
        res.init(session.inboundTrafficController());
        final SessionProtocol sessionProtocol = session.protocol();
        if (sessionProtocol == null) {
            res.close(ClosedSessionException.get());
            return;
        }

        if (session.invoke(ctx, req, res)) {
            // Return the channel to the pool.
            final KeyedChannelPool<PoolKey> pool = KeyedChannelPool.findPool(channel);
            if (sessionProtocol.isMultiplex()) {
                pool.release(poolKey, channel);
            } else {
                req.closeFuture()
                   .handle((ret, cause) -> pool.release(poolKey, channel))
                   .exceptionally(CompletionActions::log);
            }
        }
    }

    void close() {
        map.values().forEach(KeyedChannelPool::close);
    }
}
