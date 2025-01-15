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
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ClientConnectionTimings;
import com.linecorp.armeria.common.logging.ClientConnectionTimingsBuilder;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import com.linecorp.armeria.internal.client.HttpSession;
import com.linecorp.armeria.internal.client.PooledChannel;
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
        final Throwable throwable = ClientPendingThrowableUtil.pendingThrowable(ctx);
        if (throwable != null) {
            return earlyFailedResponse(throwable, ctx);
        }
        if (req != ctx.request()) {
            return earlyFailedResponse(
                    new IllegalStateException("ctx.request() does not match the actual request; " +
                                              "did you forget to call ctx.updateRequest() in your decorator?"),
                    ctx);
        }
        if (ctx.sessionProtocol() == SessionProtocol.UNDEFINED) {
            return earlyFailedResponse(
                    new IllegalArgumentException(
                            "ctx.sessionProtocol() cannot be '" + ctx.sessionProtocol() + "'. " +
                            "It must be one of '" + SessionProtocol.httpAndHttpsValues() + "'."), ctx);
        }

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
            return earlyFailedResponse(EmptyEndpointGroupException.get(ctx.endpointGroup()), ctx);
        }

        final SessionProtocol protocol = ctx.sessionProtocol();
        final ProxyConfig proxyConfig;
        try {
            proxyConfig = getProxyConfig(protocol, endpoint);
        } catch (Throwable t) {
            return earlyFailedResponse(t, ctx);
        }

        final Throwable cancellationCause = ctx.cancellationCause();
        if (cancellationCause != null) {
            return earlyFailedResponse(cancellationCause, ctx);
        }

        final Endpoint endpointWithPort = endpoint.withDefaultPort(ctx.sessionProtocol());
        final EventLoop eventLoop = ctx.eventLoop().withoutContext();
        // TODO(ikhoon) Use ctx.exchangeType() to create an optimized HttpResponse for non-streaming response.
        final DecodedHttpResponse res = new DecodedHttpResponse(eventLoop);
        updateCancellationTask(ctx, req, res);

        final ClientConnectionTimingsBuilder timingsBuilder = ClientConnectionTimings.builder();

        if (endpointWithPort.hasIpAddr() ||
            proxyConfig.proxyType().isForwardProxy()) {
            // There is no need to resolve the IP address either because it is already known,
            // or it isn't needed for forward proxies.
            acquireConnectionAndExecute(ctx, endpointWithPort, req, res, timingsBuilder, proxyConfig);
        } else {
            resolveAddress(endpointWithPort, ctx, (resolved, cause) -> {
                timingsBuilder.dnsResolutionEnd();
                if (cause == null) {
                    assert resolved != null;
                    acquireConnectionAndExecute(ctx, resolved, req, res, timingsBuilder, proxyConfig);
                } else {
                    ctx.logBuilder().session(null, ctx.sessionProtocol(), timingsBuilder.build());
                    ctx.cancel(cause);
                }
            });
        }

        return res;
    }

    private static void updateCancellationTask(ClientRequestContext ctx, HttpRequest req,
                                               DecodedHttpResponse res) {
        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        if (ctxExt == null) {
            return;
        }
        ctxExt.responseCancellationScheduler().updateTask(cause -> {
            try (SafeCloseable ignored = RequestContextUtil.pop()) {
                final UnprocessedRequestException ure = UnprocessedRequestException.of(cause);
                req.abort(ure);
                ctx.logBuilder().endRequest(ure);
                res.close(ure);
                ctx.logBuilder().endResponse(ure);
            }
        });
    }

    private void resolveAddress(Endpoint endpoint, ClientRequestContext ctx,
                                BiConsumer<@Nullable Endpoint, @Nullable Throwable> onComplete) {

        // IP address has not been resolved yet.
        assert !endpoint.hasIpAddr() && endpoint.hasPort();

        final Future<InetSocketAddress> resolveFuture =
                addressResolverGroup.getResolver(ctx.eventLoop().withoutContext())
                                    .resolve(endpoint.toSocketAddress(-1));
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
                                             ClientConnectionTimingsBuilder timingsBuilder,
                                             ProxyConfig proxyConfig) {
        if (ctx.eventLoop().inEventLoop()) {
            acquireConnectionAndExecute0(ctx, endpoint, req, res, timingsBuilder, proxyConfig);
        } else {
            ctx.eventLoop().execute(() -> {
                acquireConnectionAndExecute0(ctx, endpoint, req, res, timingsBuilder, proxyConfig);
            });
        }
    }

    private void acquireConnectionAndExecute0(ClientRequestContext ctx, Endpoint endpoint,
                                              HttpRequest req, DecodedHttpResponse res,
                                              ClientConnectionTimingsBuilder timingsBuilder,
                                              ProxyConfig proxyConfig) {
        final PoolKey key = new PoolKey(endpoint, proxyConfig);
        final HttpChannelPool pool;
        try {
            pool = factory.pool(ctx.eventLoop().withoutContext());
        } catch (Throwable t) {
            ctx.cancel(t);
            return;
        }
        final SessionProtocol protocol = ctx.sessionProtocol();
        final SerializationFormat serializationFormat = ctx.log().partial().serializationFormat();
        final PooledChannel pooledChannel = pool.acquireNow(protocol, serializationFormat, key);
        if (pooledChannel != null) {
            logSession(ctx, pooledChannel, null);
            doExecute(pooledChannel, ctx, req, res);
        } else {
            pool.acquireLater(protocol, serializationFormat, key, timingsBuilder)
                .handle((newPooledChannel, cause) -> {
                    logSession(ctx, newPooledChannel, timingsBuilder.build());
                    if (cause == null) {
                        doExecute(newPooledChannel, ctx, req, res);
                    } else {
                        ctx.cancel(cause);
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

    private static HttpResponse earlyFailedResponse(Throwable t, ClientRequestContext ctx) {
        final UnprocessedRequestException cause = UnprocessedRequestException.of(t);
        ctx.cancel(cause);
        return HttpResponse.ofFailure(cause);
    }

    private static void doExecute(PooledChannel pooledChannel, ClientRequestContext ctx,
                                  HttpRequest req, DecodedHttpResponse res) {
        final Channel channel = pooledChannel.get();
        final HttpSession session = HttpSession.get(channel);
        res.init(session.inboundTrafficController());
        session.invoke(pooledChannel, ctx, req, res);
    }
}
