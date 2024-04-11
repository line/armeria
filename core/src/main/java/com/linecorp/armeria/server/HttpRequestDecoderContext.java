/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.server;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.internal.common.RequestContextUtil.NOOP_CONTEXT_HOOK;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.function.Supplier;

import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.internal.common.util.ChannelUtil;
import com.linecorp.armeria.internal.server.DefaultServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

final class HttpRequestDecoderContext {

    private static final Logger logger = LoggerFactory.getLogger(HttpRequestDecoderContext.class);

    private static boolean warnedNullRequestId;

    private static boolean warnedRequestIdGenerateFailure;

    private static final InetSocketAddress UNKNOWN_ADDR;

    static {
        InetAddress unknownAddr;
        try {
            unknownAddr = InetAddress.getByAddress("<unknown>", new byte[] { 0, 0, 0, 0 });
        } catch (Exception e1) {
            // Just in case a certain JRE implementation doesn't accept the hostname '<unknown>'
            try {
                unknownAddr = InetAddress.getByAddress(new byte[] { 0, 0, 0, 0 });
            } catch (Exception e2) {
                // Should never reach here.
                final Error err = new Error(e2);
                err.addSuppressed(e1);
                throw err;
            }
        }
        UNKNOWN_ADDR = new InetSocketAddress(unknownAddr, 1);
    }

    private final ServerConfig config;

    private SessionProtocol protocol;

    @Nullable
    private final ProxiedAddresses proxiedAddresses;

    @Nullable
    private SSLSession sslSession;

    @Nullable
    private InetSocketAddress remoteAddress;
    @Nullable
    private InetSocketAddress localAddress;

    HttpRequestDecoderContext(ServerConfig config, SessionProtocol protocol,
                              @Nullable ProxiedAddresses proxiedAddresses) {
        this.config = config;
        this.protocol = protocol;
        this.proxiedAddresses = proxiedAddresses;
    }

    void setProtocol(SessionProtocol protocol) {
        this.protocol = protocol;
    }

    SessionProtocol protocol() {
        return protocol;
    }

    void setSslSession(SSLSession sslSession) {
        this.sslSession = sslSession;
    }

    DefaultServiceRequestContext newServiceRequestContext(Channel channel, Routed<ServiceConfig> routed,
                                                          DecodedHttpRequest req) {
        final RoutingResult routingResult = routed.routingResult();
        final ServiceConfig serviceCfg = routed.value();
        final EventLoop serviceEventLoop;
        final EventLoopGroup serviceWorkerGroup = serviceCfg.serviceWorkerGroup();
        if (serviceWorkerGroup == config.workerGroup()) {
            serviceEventLoop = channel.eventLoop();
        } else {
            serviceEventLoop = serviceWorkerGroup.next();
        }
        final MeterRegistry meterRegistry = config.meterRegistry();
        final Supplier<AutoCloseable> contextHook = serviceCfg.contextHook();

        return newServiceRequestContext(channel, req, routingResult, serviceCfg, serviceEventLoop,
                                        meterRegistry, contextHook);
    }

    private DefaultServiceRequestContext newServiceRequestContext(
            Channel channel,
            DecodedHttpRequest req,
            RoutingResult routingResult,
            ServiceConfig serviceCfg,
            EventLoop serviceEventLoop,
            MeterRegistry meterRegistry,
            Supplier<AutoCloseable> contextHook) {
        final InetSocketAddress remoteAddress = firstNonNull(remoteAddress(channel), UNKNOWN_ADDR);
        final InetSocketAddress localAddress = firstNonNull(localAddress(channel), UNKNOWN_ADDR);
        final ProxiedAddresses proxiedAddresses = determineProxiedAddresses(remoteAddress, req.headers());
        final InetAddress clientAddress = config.clientAddressMapper().apply(proxiedAddresses).getAddress();

        final DefaultServiceRequestContext ctx = new DefaultServiceRequestContext(
                serviceCfg, channel, serviceEventLoop, meterRegistry, protocol,
                nextRequestId(req.routingContext(), serviceCfg), req.routingContext(), routingResult,
                req.exchangeType(), req, sslSession, proxiedAddresses, clientAddress, remoteAddress,
                localAddress,
                req.requestStartTimeNanos(), req.requestStartTimeMicros(), contextHook);
        req.setServiceRequestContext(ctx);
        return ctx;
    }

    DefaultServiceRequestContext newOptionsRequestContext(Channel channel, DecodedHttpRequest req) {
        final RoutingContext routingCtx = req.routingContext();
        final ServiceConfig serviceCfg = routingCtx.virtualHost().fallbackServiceConfig();
        final RoutingResult routingResult = RoutingResult.builder()
                                                         .path(routingCtx.path())
                                                         .build();
        return newServiceRequestContext(channel, req, routingResult, serviceCfg, channel.eventLoop(),
                                        NoopMeterRegistry.get(), NOOP_CONTEXT_HOOK);
    }

    private ProxiedAddresses determineProxiedAddresses(InetSocketAddress remoteAddress,
                                                       RequestHeaders headers) {
        if (config.clientAddressTrustedProxyFilter().test(remoteAddress.getAddress())) {
            return HttpHeaderUtil.determineProxiedAddresses(
                    headers, config.clientAddressSources(), proxiedAddresses,
                    remoteAddress, config.clientAddressFilter());
        } else {
            return proxiedAddresses != null ? proxiedAddresses : ProxiedAddresses.of(remoteAddress);
        }
    }

    @Nullable
    private InetSocketAddress remoteAddress(Channel ch) {
        final InetSocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress != null) {
            return remoteAddress;
        }

        final InetSocketAddress newRemoteAddress = ChannelUtil.remoteAddress(ch);
        this.remoteAddress = newRemoteAddress;
        return newRemoteAddress;
    }

    @Nullable
    private InetSocketAddress localAddress(Channel ch) {
        final InetSocketAddress localAddress = this.localAddress;
        if (localAddress != null) {
            return localAddress;
        }

        final InetSocketAddress newLocalAddress = ChannelUtil.localAddress(ch);
        this.localAddress = newLocalAddress;
        return newLocalAddress;
    }

    private static RequestId nextRequestId(RoutingContext routingCtx, ServiceConfig serviceConfig) {
        try {
            final RequestId id = serviceConfig.requestIdGenerator().apply(routingCtx);
            if (id != null) {
                return id;
            }

            if (!warnedNullRequestId) {
                warnedNullRequestId = true;
                logger.warn("requestIdGenerator.apply(routingCtx) returned null; using RequestId.random()");
            }
            return RequestId.random();
        } catch (Exception e) {
            if (!warnedRequestIdGenerateFailure) {
                warnedRequestIdGenerateFailure = true;
                logger.warn("requestIdGenerator.apply(routingCtx) threw an exception; using RequestId.random()",
                            e);
            }
            return RequestId.random();
        }
    }
}
