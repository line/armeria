/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.internal.testing.netty;

import java.net.URI;

import javax.net.ssl.SSLException;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.Http2Frame;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2Stream.State;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.handler.ssl.SslContext;

public final class SimpleHttp2Connection implements SafeCloseable {

    public static SimpleHttp2Connection of(URI uri) {
        return of(uri.getHost(), uri.getPort(), null);
    }

    public static SimpleHttp2Connection of(URI uri, Http2FrameLogger frameLogger) {
        return of(uri.getHost(), uri.getPort(), frameLogger);
    }

    public static SimpleHttp2Connection of(String host, int port, @Nullable Http2FrameLogger frameLogger) {
        try {
            return new SimpleHttp2Connection(host, port, null, frameLogger);
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }
    }

    private final EventLoopGroup clientWorkerGroup = new NioEventLoopGroup();
    private final Channel channel;

    private SimpleHttp2Connection(String host, int port, @Nullable SslContext sslCtx,
                                  @Nullable Http2FrameLogger frameLogger) throws SSLException {
        final Bootstrap b = new Bootstrap();
        b.group(clientWorkerGroup);
        b.channel(NioSocketChannel.class);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.remoteAddress(host, port);
        b.handler(new Http2ClientFrameInitializer(sslCtx, frameLogger));

        // Start the client.
        channel = b.connect().syncUninterruptibly().channel();
    }

    public Channel channel() {
        return channel;
    }

    public Http2Stream createStream() {
        try {
            return new Http2Stream(channel);
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        channel.close().syncUninterruptibly();
        clientWorkerGroup.shutdownGracefully();
    }

    public static class Http2Stream implements SafeCloseable {

        private final Http2StreamChannel streamChannel;
        private final HttpFrameAggregator aggregator = new HttpFrameAggregator();

        Http2Stream(Channel channel) throws SSLException {
            final Http2StreamChannelBootstrap streamChannelBootstrap = new Http2StreamChannelBootstrap(channel);
            streamChannel = streamChannelBootstrap.open().syncUninterruptibly().getNow();
            streamChannel.pipeline().addLast(aggregator);
        }

        public Http2FrameStream frameStream() {
            return streamChannel.stream();
        }

        public boolean isOpen() {
            final State state = frameStream().state();
            return state.localSideOpen() || state.remoteSideOpen();
        }

        public boolean isEmpty() {
            return aggregator.frames().isEmpty();
        }

        public ChannelFuture sendFrame(Http2StreamFrame frame) {
            return streamChannel.writeAndFlush(frame);
        }

        public Http2Frame take() {
            try {
                return aggregator.frames().take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
            aggregator.close();
            streamChannel.close();
        }
    }
}
