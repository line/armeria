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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

import com.linecorp.armeria.client.HttpChannelPool.PoolKey;
import com.linecorp.armeria.client.endpoint.EmptyEndpointGroupException;
import com.linecorp.armeria.client.proxy.HAProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ClientConnectionTimings;
import com.linecorp.armeria.common.logging.ClientConnectionTimingsBuilder;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.PathAndQuery;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.armeria.server.ProxiedAddresses;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.Future;

final class HttpClientDelegate implements HttpClient {

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
            // It is possible that we reach here even when `EndpointGroup` is not empty,
            // because `endpoint` can be `null` for the following two cases:
            // - `EndpointGroup.select()` returned `null`.
            // - An exception was raised while context initialization.
            //
            // Because all the clean-up is done by `DefaultClientRequestContext.failEarly()`
            // when context initialization fails with an exception, we can assume that the exception
            // and response created here will be exposed only when `EndpointGroup.select()` returned `null`.
            //
            // See `DefaultClientRequestContext.init()` for more information.
            final UnprocessedRequestException cause =
                    UnprocessedRequestException.of(EmptyEndpointGroupException.get(ctx.endpointGroup()));
            handleEarlyRequestException(ctx, req, cause);
            return HttpResponse.ofFailure(cause);
        }

        if (!isValidPath(req)) {
            final UnprocessedRequestException cause = UnprocessedRequestException.of(
                    new IllegalArgumentException("invalid path: " + req.path()));
            handleEarlyRequestException(ctx, req, cause);
            return HttpResponse.ofFailure(cause);
        }

        final Endpoint endpointWithPort = endpoint.withDefaultPort(ctx.sessionProtocol().defaultPort());
        final EventLoop eventLoop = ctx.eventLoop().withoutContext();
        final DecodedHttpResponse res = new DecodedHttpResponse(eventLoop);

        final ClientConnectionTimingsBuilder timingsBuilder = ClientConnectionTimings.builder();

        if (endpointWithPort.hasIpAddr()) {
            // IP address has been resolved already.
            acquireConnectionAndExecute(ctx, endpointWithPort, req, res, timingsBuilder);
        } else {
            resolveAddress(endpointWithPort, ctx, (resolved, cause) -> {
                timingsBuilder.dnsResolutionEnd();
                if (cause == null) {
                    assert resolved != null;
                    acquireConnectionAndExecute(ctx, resolved, req, res, timingsBuilder);
                } else {
                    ctx.logBuilder().session(null, ctx.sessionProtocol(), timingsBuilder.build());
                    final UnprocessedRequestException wrappedCause = UnprocessedRequestException.of(cause);
                    handleEarlyRequestException(ctx, req, wrappedCause);
                    res.close(wrappedCause);
                }
            });
        }

        return res;
    }

    private void resolveAddress(Endpoint endpoint, ClientRequestContext ctx,
                                BiConsumer<@Nullable Endpoint, @Nullable Throwable> onComplete) {

        // IP address has not been resolved yet.
        assert !endpoint.hasIpAddr() && endpoint.hasPort();

        final Future<InetSocketAddress> resolveFuture =
                addressResolverGroup.getResolver(ctx.eventLoop().withoutContext())
                                    .resolve(InetSocketAddress.createUnresolved(endpoint.host(),
                                                                                endpoint.port()));
        if (resolveFuture.isSuccess()) {
            final InetAddress address = resolveFuture.getNow().getAddress();
            onComplete.accept(endpoint.withInetAddress(address), null);
        } else {
            resolveFuture.addListener(future -> {
                if (future.isSuccess()) {
                    final InetAddress address = resolveFuture.getNow().getAddress();
                    onComplete.accept(endpoint.withInetAddress(address), null);
                } else {
                    onComplete.accept(null, resolveFuture.cause());
                }
            });
        }
    }

    private void acquireConnectionAndExecute(ClientRequestContext ctx, Endpoint endpoint,
                                             HttpRequest req, DecodedHttpResponse res,
                                             ClientConnectionTimingsBuilder timingsBuilder) {
        if (ctx.eventLoop().inEventLoop()) {
            acquireConnectionAndExecute0(ctx, endpoint, req, res, timingsBuilder);
        } else {
            ctx.eventLoop().execute(() -> {
                acquireConnectionAndExecute0(ctx, endpoint, req, res, timingsBuilder);
            });
        }
    }

    private void acquireConnectionAndExecute0(ClientRequestContext ctx, Endpoint endpoint,
                                              HttpRequest req, DecodedHttpResponse res,
                                              ClientConnectionTimingsBuilder timingsBuilder) {
        // IP address should be resolved already.
        assert endpoint.hasIpAddr();

        final SessionProtocol protocol = ctx.sessionProtocol();
        final HttpChannelPool pool = factory.pool(ctx.eventLoop().withoutContext());

        final ProxyConfig proxyConfig;
        try {
            proxyConfig = getProxyConfig(protocol, endpoint);
        } catch (Throwable t) {
            final UnprocessedRequestException wrapped = UnprocessedRequestException.of(t);
            handleEarlyRequestException(ctx, req, wrapped);
            res.close(wrapped);
            return;
        }

        final PoolKey key = new PoolKey(endpoint.host(), endpoint.ipAddr(), endpoint.port(), proxyConfig);
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
                    final UnprocessedRequestException wrapped = UnprocessedRequestException.of(cause);
                    handleEarlyRequestException(ctx, req, wrapped);
                    res.close(wrapped);
                }
                return null;
            });
        }
    }

    private ProxyConfig getProxyConfig(SessionProtocol protocol, Endpoint endpoint) {
        final ProxyConfig proxyConfig = factory.proxyConfigSelector().select(protocol, endpoint);
        requireNonNull(proxyConfig, "proxyConfig");

        // special behavior for haproxy when sourceAddress is null
        if (proxyConfig.proxyType() == ProxyType.HAPROXY &&
            ((HAProxyConfig) proxyConfig).sourceAddress() == null) {
            final InetSocketAddress proxyAddress = proxyConfig.proxyAddress();
            assert proxyAddress != null;

            // use proxy information in context if available
            final ServiceRequestContext serviceCtx = ServiceRequestContext.currentOrNull();
            if (serviceCtx != null) {
                final ProxiedAddresses proxiedAddresses = serviceCtx.proxiedAddresses();
                return ProxyConfig.haproxy(proxyAddress, proxiedAddresses.sourceAddress());
            }
        }

        return proxyConfig;
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

    private static void doExecute(PooledChannel pooledChannel, ClientRequestContext ctx,
                                  HttpRequest req, DecodedHttpResponse res) {
        final Channel channel = pooledChannel.get();
        final HttpSession session = HttpSession.get(channel);
        res.init(session.inboundTrafficController());
        session.invoke(pooledChannel, ctx, req, res);
    }
}
