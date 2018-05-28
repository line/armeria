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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.pool.KeyedChannelPool;
import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.PathAndQuery;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;

final class HttpClientDelegate implements Client<HttpRequest, HttpResponse> {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientDelegate.class);

    private final HttpClientFactory factory;

    HttpClientDelegate(HttpClientFactory factory) {
        this.factory = requireNonNull(factory, "factory");
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final Endpoint endpoint = ctx.endpoint().resolve(ctx)
                                     .withDefaultPort(ctx.sessionProtocol().defaultPort());
        if (!sanitizePath(req)) {
            req.abort();
            return HttpResponse.ofFailure(new IllegalArgumentException("invalid path: " + req.path()));
        }

        final EventLoop eventLoop = ctx.eventLoop();
        final PoolKey poolKey = new PoolKey(endpoint.host(), endpoint.ipAddr(),
                                            endpoint.port(), ctx.sessionProtocol());
        final Future<Channel> channelFuture = factory.pool(eventLoop).acquire(poolKey);
        final DecodedHttpResponse res = new DecodedHttpResponse(eventLoop);

        if (channelFuture.isDone()) {
            if (channelFuture.isSuccess()) {
                final Channel ch = channelFuture.getNow();
                invoke0(ch, ctx, req, res, poolKey);
            } else {
                res.close(channelFuture.cause());
            }
        } else {
            channelFuture.addListener((Future<Channel> future) -> {
                if (future.isSuccess()) {
                    final Channel ch = future.getNow();
                    invoke0(ch, ctx, req, res, poolKey);
                } else {
                    res.close(channelFuture.cause());
                }
            });
        }

        return res;
    }

    private static boolean sanitizePath(HttpRequest req) {
        final PathAndQuery pathAndQuery = PathAndQuery.parse(req.path());
        if (pathAndQuery == null) {
            return false;
        }

        final String path = pathAndQuery.path();
        final String query = pathAndQuery.query();
        final String newPathAndQuery;
        if (query != null) {
            newPathAndQuery = path + '?' + query;
        } else {
            newPathAndQuery = path;
        }

        req.path(newPathAndQuery);
        return true;
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
                    final CompletableFuture<Void> completionFuture =
                            factory.useHttp1Pipelining() ? req.completionFuture() : res.completionFuture();
                    completionFuture.whenComplete((ret, cause) -> release(pool, poolKey, channel));
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
}
