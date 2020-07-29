/*
 * Copyright 2020 LINE Corporation
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

import static io.netty.handler.codec.haproxy.HAProxyCommand.PROXY;
import static io.netty.handler.codec.haproxy.HAProxyProtocolVersion.V2;
import static io.netty.handler.codec.haproxy.HAProxyProxiedProtocol.TCP4;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.proxy.HAProxyConfig;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageEncoder;
import io.netty.handler.proxy.ProxyConnectException;

final class HAProxyHandler extends ChannelOutboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(HAProxyHandler.class);

    private final HAProxyConfig haProxyConfig;

    HAProxyHandler(HAProxyConfig haProxyConfig) {
        this.haProxyConfig = haProxyConfig;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().addBefore(ctx.name(), null, HAProxyMessageEncoder.INSTANCE);
        super.handlerAdded(ctx);
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
                        SocketAddress localAddress, ChannelPromise promise) throws Exception {
        promise.addListener(f -> {
            if (!f.isSuccess()) {
                return;
            }
            try {
                ctx.write(createMessage(haProxyConfig, ctx.channel())).addListener(f0 -> {
                    if (f0.isSuccess()) {
                        ctx.pipeline().remove(HAProxyMessageEncoder.INSTANCE);
                    } else {
                        ctx.fireExceptionCaught(wrapException(f0.cause()));
                        ctx.close();
                    }
                });
            } catch (Exception e) {
                ctx.channel().eventLoop().execute(() -> {
                    ctx.pipeline().fireUserEventTriggered(wrapException(e));
                    ctx.close();
                });
            } finally {
                ctx.pipeline().remove(this);
            }
        });
        super.connect(ctx, remoteAddress, localAddress, promise);
    }

    ProxyConnectException wrapException(Throwable e) {
        if (e instanceof ProxyConnectException) {
            return (ProxyConnectException) e;
        }
        return new ProxyConnectException(e);
    }

    private static HAProxyMessage createMessage(HAProxyConfig haProxyConfig,
                                                Channel channel) throws ProxyConnectException {
        final InetSocketAddress srcSocketAddress =
                haProxyConfig.sourceAddress() != null ? haProxyConfig.sourceAddress()
                                                      : (InetSocketAddress) channel.localAddress();
        final InetSocketAddress destSocketAddress = haProxyConfig.proxyAddress();
        assert destSocketAddress != null;

        final InetAddress srcAddress = srcSocketAddress.getAddress();
        final InetAddress destAddress = destSocketAddress.getAddress();

        if (srcAddress instanceof Inet4Address && destAddress instanceof Inet4Address) {
            return new HAProxyMessage(V2, PROXY, TCP4,
                                      srcAddress.getHostAddress(), destAddress.getHostAddress(),
                                      srcSocketAddress.getPort(), destSocketAddress.getPort());
        } else if (srcAddress instanceof Inet6Address && destAddress instanceof Inet6Address) {
            return new HAProxyMessage(V2, PROXY, TCP4,
                                      srcAddress.getHostAddress(), destAddress.getHostAddress(),
                                      srcSocketAddress.getPort(), destSocketAddress.getPort());
        } else {
            logger.warn("Incompatible PROXY address types. srcAddress: {}, destAddress: {}",
                        srcAddress.getClass(), destAddress.getClass());
            throw new ProxyConnectException("incompatible addresses: [" + srcAddress + '-' +
                                            destAddress + ']');
        }
    }
}

