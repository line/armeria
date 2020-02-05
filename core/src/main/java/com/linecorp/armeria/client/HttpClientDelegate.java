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
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.ClientConnectionTimings;
import com.linecorp.armeria.common.logging.ClientConnectionTimingsBuilder;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.PathAndQuery;
import com.linecorp.armeria.internal.common.RequestContextUtil;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

final class HttpClientDelegate implements HttpClient {

    private static final Throwable CONTEXT_INITIALIZATION_FAILED = new Exception(
            ClientRequestContext.class.getSimpleName() + " initialization failed", null, false, false) {
        private static final long serialVersionUID = 837901495421033459L;
    };

    private final HttpClientFactory factory;
    private final AddressResolverGroup<InetSocketAddress> addressResolverGroup;

    HttpClientDelegate(HttpClientFactory factory,
                       AddressResolverGroup<InetSocketAddress> addressResolverGroup) {
        this.factory = requireNonNull(factory, "factory");
        this.addressResolverGroup = requireNonNull(addressResolverGroup, "addressResolverGroup");
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final Endpoint endpoint = ctx.endpoint();
        if (endpoint == null) {
            // Note that this response will be ignored because:
            // - `ClientRequestContext.endpoint()` returns `null` only when the context initialization failed.
            // - `ClientUtil.initContextAndExecuteWithFallback()` will use the fallback response rather than
            //   what we return here.
            req.abort(CONTEXT_INITIALIZATION_FAILED);
            return HttpResponse.ofFailure(CONTEXT_INITIALIZATION_FAILED);
        }

        if (!isValidPath(req)) {
            final IllegalArgumentException cause = new IllegalArgumentException("invalid path: " + req.path());
            handleEarlyRequestException(ctx, req, cause);
            return HttpResponse.ofFailure(cause);
        }

        final Endpoint endpointWithPort = endpoint.withDefaultPort(ctx.sessionProtocol().defaultPort());
        final EventLoop eventLoop = ctx.eventLoop();
        final DecodedHttpResponse res = new DecodedHttpResponse(eventLoop);

        final ClientConnectionTimingsBuilder timingsBuilder = ClientConnectionTimings.builder();

        if (endpointWithPort.hasIpAddr()) {
            // IP address has been resolved already.
            acquireConnectionAndExecute(ctx, endpointWithPort, endpointWithPort.ipAddr(),
                                        req, res, timingsBuilder);
        } else {
            // IP address has not been resolved yet.
            final Future<InetSocketAddress> resolveFuture =
                    addressResolverGroup.getResolver(eventLoop)
                                        .resolve(InetSocketAddress.createUnresolved(endpointWithPort.host(),
                                                                                    endpointWithPort.port()));
            if (resolveFuture.isDone()) {
                finishResolve(ctx, endpointWithPort, resolveFuture, req, res, timingsBuilder);
            } else {
                resolveFuture.addListener(
                        (FutureListener<InetSocketAddress>) future ->
                                finishResolve(ctx, endpointWithPort, future, req, res, timingsBuilder));
            }
        }

        return res;
    }

    private void finishResolve(ClientRequestContext ctx, Endpoint endpointWithPort,
                               Future<InetSocketAddress> resolveFuture, HttpRequest req,
                               DecodedHttpResponse res, ClientConnectionTimingsBuilder timingsBuilder) {
        timingsBuilder.dnsResolutionEnd();
        if (resolveFuture.isSuccess()) {
            final String ipAddr = resolveFuture.getNow().getAddress().getHostAddress();
            acquireConnectionAndExecute(ctx, endpointWithPort, ipAddr, req, res, timingsBuilder);
        } else {
            ctx.logBuilder().session(null, ctx.sessionProtocol(), timingsBuilder.build());
            final Throwable cause = resolveFuture.cause();
            handleEarlyRequestException(ctx, req, cause);
            res.close(cause);
        }
    }

    private void acquireConnectionAndExecute(ClientRequestContext ctx, Endpoint endpointWithPort,
                                             String ipAddr, HttpRequest req, DecodedHttpResponse res,
                                             ClientConnectionTimingsBuilder timingsBuilder) {
        final EventLoop eventLoop = ctx.eventLoop();
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> acquireConnectionAndExecute(ctx, endpointWithPort, ipAddr,
                                                                req, res, timingsBuilder));
            return;
        }

        final String host = extractHost(ctx, req, endpointWithPort);
        final int port = endpointWithPort.port();
        final SessionProtocol protocol = ctx.sessionProtocol();
        final HttpChannelPool pool = factory.pool(ctx.eventLoop());

        final PoolKey key = new PoolKey(host, ipAddr, port);
        final PooledChannel pooledChannel = pool.acquireNow(protocol, key);
        if (pooledChannel != null) {
            logSession(ctx, pooledChannel, null);
            doExecute(pooledChannel, ctx, req, res);
        } else {
            pool.acquireLater(protocol, key, timingsBuilder).handle((newPooledChannel, cause) -> {
                logSession(ctx, newPooledChannel, timingsBuilder.build());
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

    private static void logSession(ClientRequestContext ctx, @Nullable PooledChannel pooledChannel,
                                   @Nullable ClientConnectionTimings connectionTimings) {
        if (pooledChannel != null) {
            final Channel channel = pooledChannel.get();
            final SessionProtocol actualProtocol = pooledChannel.protocol();
            ctx.logBuilder().session(channel, actualProtocol, connectionTimings);
        } else {
            ctx.logBuilder().session(null, ctx.sessionProtocol(), connectionTimings);
        }
    }

    @VisibleForTesting
    static String extractHost(ClientRequestContext ctx, HttpRequest req, Endpoint endpoint) {
        String host = extractHost(ctx.additionalRequestHeaders().get(HttpHeaderNames.AUTHORITY));
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
        try (SafeCloseable ignored = RequestContextUtil.pop()) {
            req.abort(cause);
            final RequestLogBuilder logBuilder = ctx.logBuilder();
            logBuilder.endRequest(cause);
            logBuilder.endResponse(cause);
        }
    }

    private void doExecute(PooledChannel pooledChannel, ClientRequestContext ctx,
                           HttpRequest req, DecodedHttpResponse res) {
        final Channel channel = pooledChannel.get();
        boolean needsRelease = true;
        try {
            final HttpSession session = HttpSession.get(channel);
            res.init(session.inboundTrafficController());
            final SessionProtocol sessionProtocol = session.protocol();

            // Should never reach here.
            if (sessionProtocol == null) {
                needsRelease = false;
                try {
                    // TODO(minwoox): Make a test that handles this case
                    final NullPointerException cause = new NullPointerException("sessionProtocol");
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
                            factory.useHttp1Pipelining() ? req.whenComplete() : res.whenComplete();
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
