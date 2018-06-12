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

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import com.linecorp.armeria.client.pool.KeyedChannelPool;
import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.PathAndQuery;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

final class HttpClientDelegate implements Client<HttpRequest, HttpResponse> {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientDelegate.class);

    private final HttpClientFactory factory;
    private final AddressResolverGroup<InetSocketAddress> addressResolverGroup;

    HttpClientDelegate(HttpClientFactory factory,
                       AddressResolverGroup<InetSocketAddress> addressResolverGroup) {
        this.factory = requireNonNull(factory, "factory");
        this.addressResolverGroup = requireNonNull(addressResolverGroup, "addressResolverGroup");
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        if (!sanitizePath(req)) {
            req.abort();
            return HttpResponse.ofFailure(new IllegalArgumentException("invalid path: " + req.path()));
        }

        final Endpoint endpoint = ctx.endpoint().resolve(ctx)
                                     .withDefaultPort(ctx.sessionProtocol().defaultPort());
        final EventLoop eventLoop = ctx.eventLoop();
        final DecodedHttpResponse res = new DecodedHttpResponse(eventLoop);

        if (endpoint.hasIpAddr()) {
            // IP address has been resolved already.
            executeWithIpAddr(ctx, endpoint, endpoint.ipAddr(), req, res);
        } else {
            // IP address has not been resolved yet.
            final Future<InetSocketAddress> resolveFuture =
                    addressResolverGroup.getResolver(eventLoop)
                                        .resolve(InetSocketAddress.createUnresolved(endpoint.host(),
                                                                                    endpoint.port()));
            if (resolveFuture.isDone()) {
                finishResolve(ctx, endpoint, resolveFuture, req, res);
            } else {
                resolveFuture.addListener(
                        (FutureListener<InetSocketAddress>) future ->
                                finishResolve(ctx, endpoint, future, req, res));
            }
        }

        return res;
    }

    private void finishResolve(ClientRequestContext ctx, Endpoint endpoint,
                               Future<InetSocketAddress> resolveFuture, HttpRequest req,
                               DecodedHttpResponse res) {
        if (resolveFuture.isSuccess()) {
            executeWithIpAddr(ctx, endpoint, resolveFuture.getNow().getAddress().getHostAddress(), req, res);
        } else {
            res.close(resolveFuture.cause());
        }
    }

    private void executeWithIpAddr(ClientRequestContext ctx, Endpoint endpoint, String ipAddr,
                                   HttpRequest req, DecodedHttpResponse res) {
        final String host = extractHost(ctx, req, endpoint);
        final PoolKey poolKey = new PoolKey(host, ipAddr, endpoint.port(), ctx.sessionProtocol());
        final Future<Channel> channelFuture = factory.pool(ctx.eventLoop()).acquire(poolKey);

        if (channelFuture.isDone()) {
            finishExecute(ctx, poolKey, channelFuture, req, res);
        } else {
            channelFuture.addListener(
                    (Future<Channel> future) -> finishExecute(ctx, poolKey, future, req, res));
        }
    }

    private void finishExecute(ClientRequestContext ctx, PoolKey poolKey, Future<Channel> channelFuture,
                               HttpRequest req, DecodedHttpResponse res) {
        if (channelFuture.isSuccess()) {
            final Channel ch = channelFuture.getNow();
            invoke0(ch, ctx, req, res, poolKey);
        } else {
            res.close(channelFuture.cause());
        }
    }

    @VisibleForTesting
    static String extractHost(ClientRequestContext ctx, HttpRequest req, Endpoint endpoint) {
        String host = extractHost(ctx.additionalRequestHeaders().authority());
        if (host != null) {
            return host;
        }

        host = extractHost(req.authority());
        if (host != null) {
            return host;
        }

        return endpoint.host();
    }

    @Nullable
    private static String extractHost(@Nullable String authority) {
        if (Strings.isNullOrEmpty(authority)) {
            return null;
        }

        if (authority.charAt(0) == '[') {
            // Surrounded by '[' and ']'
            final int closingBracketPos = authority.lastIndexOf(']');
            if (closingBracketPos > 0) {
                return authority.substring(1, closingBracketPos);
            } else {
                // Invalid authority - no matching ']'
                return null;
            }
        }

        // Not surrounded by '[' and ']'
        final int colonPos = authority.lastIndexOf(':');
        if (colonPos > 0) {
            // Strip the port number.
            return authority.substring(0, colonPos);
        }
        if (colonPos < 0) {
            // authority does not have a port number.
            return authority;
        }

        // Invalid authority - ':' is the first character.
        return null;
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
