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

import static com.linecorp.armeria.internal.common.util.IpAddrUtil.isCreatedWithIpAddressOnly;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.function.BiConsumer;

import com.linecorp.armeria.client.HttpChannelPool.PoolKey;
import com.linecorp.armeria.client.endpoint.EmptyEndpointGroupException;
import com.linecorp.armeria.client.proxy.HAProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.IpAddressRejectedException;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.TlsProvider;
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
import com.linecorp.armeria.internal.common.SchemeAndAuthority;
import com.linecorp.armeria.server.ProxiedAddresses;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.NetUtil;
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

        final Endpoint endpointWithPort = endpoint.withDefaultPort(ctx.sessionProtocol());
        final EventLoop eventLoop = ctx.eventLoop().withoutContext();
        // TODO(ikhoon) Use ctx.exchangeType() to create an optimized HttpResponse for non-streaming response.
        final DecodedHttpResponse res = new DecodedHttpResponse(eventLoop);
        updateCancellationTask(ctx, req, res);

        factory.clientRequestLifecycleListener().onRequestPending(ctx);
        ctx.log().addListener(
                factory.clientRequestLifecycleListener()
                        .asRequestLogListener()
        );

        try {
            resolveProxyConfig(protocol, endpoint, ctx, (proxyConfig, thrown) -> {
                if (thrown != null) {
                    earlyFailedResponse(thrown, ctx, res);
                } else {
                    assert proxyConfig != null;
                    execute0(ctx, endpointWithPort, req, res, proxyConfig);
                }
            });
        } catch (Throwable t) {
            return earlyFailedResponse(t, ctx);
        }
        return res;
    }

    private void execute0(ClientRequestContext ctx, Endpoint endpointWithPort, HttpRequest req,
                          DecodedHttpResponse res, ProxyConfig proxyConfig) {
        final Throwable cancellationCause = ctx.cancellationCause();
        if (cancellationCause != null) {
            earlyFailedResponse(cancellationCause, ctx, res);
            return;
        }

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
                    earlyCancelRequest(cause, ctx, timingsBuilder);
                }
            });
        }
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
        final InetSocketAddress remoteAddress = endpoint.toSocketAddress(-1);
        try {
            final boolean isValidIpAddr = factory.options().ipAddressFilter().test(remoteAddress);
            if (!isValidIpAddr) {
                final IpAddressRejectedException cause = new IpAddressRejectedException(
                        "Invalid IP address: " + remoteAddress + " (endpoint: " + endpoint + ')');
                earlyCancelRequest(cause, ctx, timingsBuilder);
                return;
            }
        } catch (Throwable t) {
            final IllegalStateException cause = new IllegalStateException(
                    "Unexpected exception from " + factory.options().ipAddressFilter(), t);
            earlyCancelRequest(cause, ctx, timingsBuilder);
            return;
        }

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
        final SessionProtocol protocol = ctx.sessionProtocol();
        if (protocol.isTls() && endpoint.isIpAddrOnly()) {
            // The connection will be established with the IP address but `host` set to the `Endpoint`
            // could be used for SNI. It would make users send HTTPS requests with CSLB or configure a reverse
            // proxy based on an authority.
            final String serverName = authorityToServerName(ctx.authority());
            if (serverName != null) {
                endpoint = endpoint.withHost(serverName);
            }
        }
        // Remove the trailing dot of the host name because SNI does not allow it.
        // https://lists.w3.org/Archives/Public/ietf-http-wg/2016JanMar/0430.html
        endpoint = endpoint.withoutTrailingDot();

        final TlsProvider tlsProvider = factory.options().tlsProvider();
        final ClientTlsSpec tlsSpec = determineTlsSpec(endpoint, protocol, tlsProvider);

        final PoolKey key = new PoolKey(endpoint, proxyConfig, tlsSpec);
        final HttpChannelPool pool;
        try {
            pool = factory.pool(ctx.eventLoop().withoutContext());
        } catch (Throwable t) {
            earlyCancelRequest(t, ctx, timingsBuilder);
            return;
        }
        final SerializationFormat serializationFormat = ctx.log().partial().serializationFormat();
        final PooledChannel pooledChannel = pool.acquireNow(protocol, serializationFormat, key);
        if (pooledChannel != null) {
            logSession(ctx, pooledChannel, null);
            doExecute(pooledChannel, ctx, req, res);
        } else {
            pool.acquireLater(protocol, serializationFormat, key, timingsBuilder)
                .handle((newPooledChannel, cause) -> {
                    if (cause == null) {
                        logSession(ctx, newPooledChannel, timingsBuilder.build());
                        doExecute(newPooledChannel, ctx, req, res);
                    } else {
                        earlyCancelRequest(cause, ctx, timingsBuilder);
                    }
                    return null;
                });
        }
    }

    private ClientTlsSpec determineTlsSpec(Endpoint endpoint, SessionProtocol sessionProtocol,
                                           TlsProvider tlsProvider) {
        if (tlsProvider != NullTlsProvider.INSTANCE) {
            TlsKeyPair keyPair = null;
            final String hostname = endpoint.toSocketAddress(-1).getHostString();
            if (hostname != null) {
                keyPair = tlsProvider.keyPair(hostname);
            }
            if (keyPair == null) {
                keyPair = tlsProvider.keyPair("*");
            }
            final ClientTlsConfig config = factory.options().tlsConfig();
            List<X509Certificate> certs = null;
            if (hostname != null) {
                certs = tlsProvider.trustedCertificates(hostname);
            }
            if (certs == null) {
                certs = tlsProvider.trustedCertificates("*");
            }

            final ClientTlsSpecBuilder builder =
                    ClientTlsSpec.builder()
                                 .tlsCustomizer(config.tlsCustomizer())
                                 .engineType(factory.options().tlsEngineType())
                                 .alpnProtocols(sessionProtocol);
            if (keyPair != null) {
                builder.tlsKeyPair(keyPair);
            }
            if (certs != null) {
                builder.trustedCertificates(certs);
            }
            if (config.tlsNoVerifySet()) {
                builder.verifierFactories(TlsPeerVerifierFactory.noVerify());
            } else if (!config.insecureHosts().isEmpty()) {
                builder.verifierFactories(TlsPeerVerifierFactory.insecureHosts(config.insecureHosts()));
            }
            return builder.build();
        }
        // proxies may use TLS, so just return the default spec
        return factory.defaultSslContexts().getClientTlsSpec(sessionProtocol.withTls());
    }

    @Nullable
    private static String authorityToServerName(@Nullable String authority) {
        if (authority == null) {
            return null;
        }
        String serverName = SchemeAndAuthority.of(null, authority).host();
        if (NetUtil.isValidIpV4Address(serverName) || NetUtil.isValidIpV6Address(serverName)) {
            return null;
        }
        serverName = serverName.trim();
        if (serverName.isEmpty()) {
            return null;
        }
        return serverName;
    }

    private void resolveProxyConfig(SessionProtocol protocol, Endpoint endpoint, ClientRequestContext ctx,
                                    BiConsumer<@Nullable ProxyConfig, @Nullable Throwable> onComplete) {
        final ProxyConfig unresolvedProxyConfig = factory.proxyConfigSelector().select(protocol, endpoint);
        requireNonNull(unresolvedProxyConfig, "unresolvedProxyConfig");
        final ProxyConfig proxyConfig = maybeSetHAProxySourceAddress(unresolvedProxyConfig);

        final InetSocketAddress proxyAddress = proxyConfig.proxyAddress();
        final boolean needsDnsResolution = proxyAddress != null && !isCreatedWithIpAddressOnly(proxyAddress);
        if (needsDnsResolution) {
            assert proxyAddress != null;
            final Future<InetSocketAddress> resolveFuture = addressResolverGroup
                    .getResolver(ctx.eventLoop().withoutContext())
                    .resolve(createUnresolvedAddressForRefreshing(proxyAddress));

            resolveFuture.addListener(future -> {
                if (future.isSuccess()) {
                    final InetSocketAddress resolvedAddress = (InetSocketAddress) future.getNow();
                    final ProxyConfig newProxyConfig = proxyConfig.withProxyAddress(resolvedAddress);
                    onComplete.accept(newProxyConfig, null);
                } else {
                    final Throwable cause = future.cause();
                    onComplete.accept(null, cause);
                }
            });
        } else {
            onComplete.accept(proxyConfig, null);
        }
    }

    private static ProxyConfig maybeSetHAProxySourceAddress(ProxyConfig proxyConfig) {
        if (proxyConfig.proxyType() != ProxyType.HAPROXY) {
            return proxyConfig;
        }
        if (((HAProxyConfig) proxyConfig).sourceAddress() != null) {
            return proxyConfig;
        }

        final ServiceRequestContext sctx = ServiceRequestContext.currentOrNull();
        final ProxiedAddresses serviceProxiedAddresses = sctx == null ? null : sctx.proxiedAddresses();
        if (serviceProxiedAddresses != null) {
            // A special behavior for haproxy when sourceAddress is null.
            // Use proxy information in the service context if available.
            final InetSocketAddress proxyAddress = proxyConfig.proxyAddress();
            assert proxyAddress != null;
            return ProxyConfig.haproxy(proxyAddress, serviceProxiedAddresses.sourceAddress());
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

    private static HttpResponse earlyFailedResponse(Throwable t,
                                                    ClientRequestContext ctx,
                                                    DecodedHttpResponse response) {
        final UnprocessedRequestException cause = UnprocessedRequestException.of(t);
        ctx.cancel(cause);
        response.close(cause);
        return response;
    }

    private static void earlyCancelRequest(Throwable t, ClientRequestContext ctx,
                                           ClientConnectionTimingsBuilder connectionTimings) {
        ctx.logBuilder().session(null, ctx.sessionProtocol(), connectionTimings.build());
        final UnprocessedRequestException cause = UnprocessedRequestException.of(t);
        ctx.cancel(cause);
    }

    private static void doExecute(PooledChannel pooledChannel, ClientRequestContext ctx,
                                  HttpRequest req, DecodedHttpResponse res) {
        final Channel channel = pooledChannel.get();
        final HttpSession session = HttpSession.get(channel);
        res.init(session.inboundTrafficController());
        session.invoke(pooledChannel, ctx, req, res);
    }

    private static InetSocketAddress createUnresolvedAddressForRefreshing(InetSocketAddress previousAddress) {
        return InetSocketAddress.createUnresolved(previousAddress.getHostString(), previousAddress.getPort());
    }
}
