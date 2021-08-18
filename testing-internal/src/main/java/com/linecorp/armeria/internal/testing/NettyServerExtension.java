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

package com.linecorp.armeria.internal.testing;

import static com.google.common.base.Preconditions.checkState;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.testing.junit5.common.AbstractAllOrEachExtension;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public abstract class NettyServerExtension extends AbstractAllOrEachExtension {

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    @Nullable
    private Channel channel;

    protected NettyServerExtension() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);
    }

    public final InetSocketAddress address() {
        checkState(channel != null);
        return (InetSocketAddress) channel.localAddress();
    }

    public final Endpoint endpoint() {
        return Endpoint.of(address().getHostString(), address().getPort());
    }

    protected abstract void configure(Channel ch) throws Exception;

    @Override
    protected void before(ExtensionContext context) throws Exception {
        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                 .channel(NioServerSocketChannel.class)
                 .childHandler(new NettyServerChannelInitializer());
        channel = bootstrap.bind("127.0.0.1", 0).sync().channel();
    }

    @Override
    protected void after(ExtensionContext context) throws Exception {
        if (channel != null) {
            channel.close().sync();
        }
        bossGroup.shutdownGracefully().get();
        workerGroup.shutdownGracefully().get();
    }

    private class NettyServerChannelInitializer extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel ch) throws Exception {
            configure(ch);
        }
    }
}
