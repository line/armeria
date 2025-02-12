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
import static io.netty.handler.codec.haproxy.HAProxyProxiedProtocol.TCP6;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import com.linecorp.armeria.client.proxy.HAProxyConfig;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageEncoder;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.proxy.ProxyConnectionEvent;
import io.netty.util.NetUtil;

final class HAProxyHandler extends ChannelOutboundHandlerAdapter {

    private final HAProxyConfig haProxyConfig;
    private static final String PROTOCOL = "haproxy";
    private static final String AUTH = "none";

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
        final InetSocketAddress proxyAddress = haProxyConfig.proxyAddress();
        assert proxyAddress != null;
        final ChannelPromise connectionPromise = ctx.newPromise();
        ctx.connect(proxyAddress, localAddress, connectionPromise);
        connectionPromise.addListener(f -> {
            if (!f.isSuccess()) {
                promise.tryFailure(wrapException(f.cause()));
                ctx.close();
                return;
            }
            try {
                ctx.writeAndFlush(createMessage(haProxyConfig, ctx.channel(), remoteAddress))
                   .addListener(f0 -> {
                       if (f0.isSuccess()) {
                           ctx.pipeline().remove(HAProxyMessageEncoder.INSTANCE);
                           final ProxyConnectionEvent event = new ProxyConnectionEvent(
                                   PROTOCOL, AUTH, proxyAddress, remoteAddress);
                           promise.trySuccess();
                           ctx.pipeline().fireUserEventTriggered(event);
                       } else {
                           promise.tryFailure(wrapException(f0.cause()));
                           ctx.close();
                       }
                   });
            } catch (Exception e) {
                promise.tryFailure(wrapException(e));
                ctx.close();
            } finally {
                ctx.pipeline().remove(this);
            }
        });
    }

    private static ProxyConnectException wrapException(Throwable e) {
        if (e instanceof ProxyConnectException) {
            return (ProxyConnectException) e;
        }
        return new ProxyConnectException(e);
    }

    private static HAProxyMessage createMessage(HAProxyConfig haProxyConfig,
                                                Channel channel, SocketAddress remoteAddress)
            throws ProxyConnectException {
        final InetSocketAddress srcSocketAddress =
                haProxyConfig.sourceAddress() != null ? haProxyConfig.sourceAddress()
                                                      : (InetSocketAddress) channel.localAddress();
        final InetSocketAddress destSocketAddress = (InetSocketAddress) remoteAddress;

        final InetAddress srcAddress = srcSocketAddress.getAddress();
        final InetAddress destAddress = destSocketAddress.getAddress();

        if (srcAddress instanceof Inet4Address && destAddress instanceof Inet4Address) {
            return new HAProxyMessage(V2, PROXY, TCP4,
                                      srcAddress.getHostAddress(), destAddress.getHostAddress(),
                                      srcSocketAddress.getPort(), destSocketAddress.getPort());
        } else {
            return new HAProxyMessage(V2, PROXY, TCP6, translateToInet6(srcAddress).getHostAddress(),
                                      translateToInet6(destAddress).getHostAddress(),
                                      srcSocketAddress.getPort(), destSocketAddress.getPort());
        }
    }

    private static Inet6Address translateToInet6(InetAddress inetAddress) {
        if (inetAddress instanceof Inet6Address) {
            return (Inet6Address) inetAddress;
        }
        return NetUtil.getByName(inetAddress.getHostAddress());
    }
}
