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

package com.linecorp.armeria.client.proxy;

import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.armeria.testing.junit.common.AbstractAllOrEachExtension;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

public class NettyServerExtension extends AbstractAllOrEachExtension {

    private final int port;
    private final ChannelHandler childHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel channel;

    public NettyServerExtension(int port, ChannelHandler childHandler) {
        this.port = port;
        this.childHandler = childHandler;
    }

    @Override
    protected void before(ExtensionContext context) throws Exception {
        final ServerBootstrap bootstrap = new ServerBootstrap();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);

        bootstrap.group(bossGroup, workerGroup)
                 .channel(NioServerSocketChannel.class)
                 .handler(new LoggingHandler(LogLevel.INFO))
                 .childHandler(childHandler);
        channel = bootstrap.bind(port).sync().channel();
    }

    @Override
    protected void after(ExtensionContext context) throws Exception {
        if (channel != null) {
            channel.close().sync();
        }
        bossGroup.shutdownGracefully().get();
        workerGroup.shutdownGracefully().get();
    }
}
