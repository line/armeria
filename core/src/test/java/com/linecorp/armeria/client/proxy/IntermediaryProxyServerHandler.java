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
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LoggingHandler;

final class IntermediaryProxyServerHandler extends ChannelInboundHandlerAdapter {
    private final ConcurrentLinkedDeque<ByteBuf> received = new ConcurrentLinkedDeque<>();
    @Nullable
    private Channel backend;
    private final String proxyType;
    private final Consumer<Boolean> callback;
    private final Map<String, InetSocketAddress> mappings;

    IntermediaryProxyServerHandler(String proxyType, Consumer<Boolean> callback) {
        this(proxyType, callback, ImmutableMap.of());
    }

    IntermediaryProxyServerHandler(String proxyType, Consumer<Boolean> callback,
                                   Map<String, InetSocketAddress> mappings) {
        this.proxyType = proxyType;
        this.callback = callback;
        this.mappings = mappings;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ProxySuccessEvent) {
            InetSocketAddress address = ((ProxySuccessEvent) evt).getBackendAddress();
            if (address.isUnresolved() && mappings.containsKey(address.getHostName())) {
                address = mappings.get(address.getHostName());
            }
            connectBackend(ctx, address).addListener(f -> {
                if (f.isSuccess()) {
                    callback.accept(true);
                    ctx.writeAndFlush(((ProxySuccessEvent) evt).getResponse());
                    if ("http".equals(proxyType)) {
                        ctx.pipeline().remove(HttpObjectAggregator.class);
                        ctx.pipeline().remove(HttpServerCodec.class);
                    }
                } else {
                    callback.accept(false);
                    ctx.close();
                }
            });
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            final ByteBuf backendMessage = (ByteBuf) msg;
            received.add(backendMessage);
            writeToBackendAndFlush();
        } else {
            throw new IllegalStateException("unexpected msg: " + msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (backend != null) {
            backend.close();
        }
        super.channelInactive(ctx);
    }

    private ChannelFuture connectBackend(
            final ChannelHandlerContext ctx, InetSocketAddress backendAddress) {
        final ChannelHandlerContext clientCtx = ctx;
        final Bootstrap b = new Bootstrap();
        b.group(ctx.channel().eventLoop());
        b.channel(NioSocketChannel.class);
        b.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline().addLast(new LoggingHandler());
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg)
                            throws Exception {
                        clientCtx.writeAndFlush(msg);
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                        clientCtx.close();
                        super.channelInactive(ctx);
                    }
                });
            }
        });
        return b.connect(backendAddress).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                clientCtx.close();
                return;
            }
            backend = f.channel();
            writeToBackendAndFlush();
        });
    }

    private void writeToBackendAndFlush() {
        if (backend != null) {
            boolean wrote = false;
            for (;;) {
                final Object msg = received.poll();
                if (msg == null) {
                    break;
                }
                backend.write(msg);
                wrote = true;
            }
            if (wrote) {
                backend.flush();
            }
        }
    }
}
