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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import com.linecorp.armeria.client.HttpChannelPool.PoolKey;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.internal.PathAndQuery;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

final class HttpClientDelegate implements Client<HttpRequest, HttpResponse> {

    private final HttpClientFactory factory;
    private final AddressResolverGroup<InetSocketAddress> addressResolverGroup;

    HttpClientDelegate(HttpClientFactory factory,
                       AddressResolverGroup<InetSocketAddress> addressResolverGroup) {
        this.factory = requireNonNull(factory, "factory");
        this.addressResolverGroup = requireNonNull(addressResolverGroup, "addressResolverGroup");
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        if (!isValidPath(req)) {
            final IllegalArgumentException cause = new IllegalArgumentException("invalid path: " + req.path());
            handleEarlyRequestException(ctx, req, cause);
            return HttpResponse.ofFailure(cause);
        }

        final Endpoint endpoint = ctx.endpoint().resolve(ctx)
                                     .withDefaultPort(ctx.sessionProtocol().defaultPort());
        final EventLoop eventLoop = ctx.eventLoop();
        final DecodedHttpResponse res = new DecodedHttpResponse(eventLoop);

        if (endpoint.hasIpAddr()) {
            // IP address has been resolved already.
            acquireConnectionAndExecute(ctx, endpoint, endpoint.ipAddr(), req, res);
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
            final String ipAddr = resolveFuture.getNow().getAddress().getHostAddress();
            acquireConnectionAndExecute(ctx, endpoint, ipAddr, req, res);
        } else {
            final Throwable cause = resolveFuture.cause();
            handleEarlyRequestException(ctx, req, cause);
            res.close(cause);
        }
    }

    private void acquireConnectionAndExecute(ClientRequestContext ctx, Endpoint endpoint, String ipAddr,
                                             HttpRequest req, DecodedHttpResponse res) {
        final EventLoop eventLoop = ctx.eventLoop();
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> acquireConnectionAndExecute(ctx, endpoint, ipAddr, req, res));
            return;
        }

        final String host = extractHost(ctx, req, endpoint);
        final int port = endpoint.port();
        final SessionProtocol protocol = ctx.sessionProtocol();
        final HttpChannelPool pool = factory.pool(ctx.eventLoop());

        final PoolKey key = new PoolKey(host, ipAddr, port);
        final PooledChannel pooledChannel = pool.acquireNow(protocol, key);
        if (pooledChannel != null) {
            doExecute(pooledChannel, ctx, req, res);
        } else {
            pool.acquire(protocol, key).handle((newPooledChannel, cause) -> {
                if (cause == null) {
                    doExecute(newPooledChannel, ctx, req, res);
                } else {
                    handleEarlyRequestException(ctx, req, cause);
                    res.close(cause);
                }
                return null;
            });
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

    private static boolean isValidPath(HttpRequest req) {
        return PathAndQuery.parse(req.path()) != null;
    }

    private static void handleEarlyRequestException(ClientRequestContext ctx,
                                                    HttpRequest req, Throwable cause) {
        req.abort();
        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.endRequest(cause);
        logBuilder.endResponse(cause);
    }

    private void doExecute(PooledChannel pooledChannel, ClientRequestContext ctx,
                           HttpRequest req, DecodedHttpResponse res) {
        final Channel channel = pooledChannel.get();
        boolean needsRelease = true;
        try {
            final HttpSession session = HttpSession.get(channel);
            res.init(session.inboundTrafficController());
            final SessionProtocol sessionProtocol = session.protocol();
            if (sessionProtocol == null) {
                needsRelease = false;
                try {
                    // TODO(minwoox): Make a test that handles this case
                    final UnprocessedRequestException cause = UnprocessedRequestException.get();
                    handleEarlyRequestException(ctx, req, cause);
                    res.close(cause);
                } finally {
                    channel.close();
                }
                return;
            }

            if (session.invoke(ctx, req, res)) {
                needsRelease = false;

                // Return the channel to the pool.
                if (!sessionProtocol.isMultiplex()) {
                    // If pipelining is enabled, return as soon as the request is fully sent.
                    // If pipelining is disabled, return after the response is fully received.
                    final CompletableFuture<Void> completionFuture =
                            factory.useHttp1Pipelining() ? req.completionFuture() : res.completionFuture();
                    completionFuture.handle((ret, cause) -> {
                        pooledChannel.release();
                        return null;
                    });
                } else {
                    // HTTP/2 connections do not need to get returned.
                }
            }
        } finally {
            if (needsRelease) {
                pooledChannel.release();
            }
        }
    }
}
