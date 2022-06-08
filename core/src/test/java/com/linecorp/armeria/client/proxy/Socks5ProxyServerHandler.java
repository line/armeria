/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.proxy;

import java.net.InetSocketAddress;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5Message;

class Socks5ProxyServerHandler extends SimpleChannelInboundHandler<Socks5Message> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Socks5Message msg) throws Exception {
        if (msg instanceof DefaultSocks5InitialRequest) {
            ctx.pipeline().addBefore(ctx.name(), Socks5CommandRequestDecoder.class.getName(),
                                     new Socks5CommandRequestDecoder());
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
        } else if (msg instanceof DefaultSocks5CommandRequest) {
            final DefaultSocks5CommandRequest req = (DefaultSocks5CommandRequest) msg;
            ctx.pipeline().remove(Socks5CommandRequestDecoder.class);
            ctx.fireUserEventTriggered(new ProxySuccessEvent(
                    new InetSocketAddress(req.dstAddr(), req.dstPort()),
                    new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS,
                                                     Socks5AddressType.IPv4)));
        } else {
            throw new IllegalStateException("unexpected msg: " + msg);
        }
    }
}
