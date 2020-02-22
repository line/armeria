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

import static com.linecorp.armeria.common.HttpStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandRequest;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandResponse;
import io.netty.handler.codec.socksx.v4.Socks4CommandStatus;
import io.netty.handler.logging.LoggingHandler;

public class ProxyClientIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ProxyClientIntegrationTest.class);
    private static final String PROXY_PATH = "/proxy";

    private static final class SocksServerInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        public void initChannel(SocketChannel ch) throws Exception {
            ch.pipeline().addLast(
                    new SocksPortUnificationServerHandler(),
                    new HttpServerCodec(),
                    new LoggingHandler(getClass()),
                    new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            if (msg instanceof DefaultSocks4CommandRequest) {
                                final DefaultSocks4CommandRequest req = (DefaultSocks4CommandRequest) msg;
                                final DefaultSocks4CommandResponse res =
                                        new DefaultSocks4CommandResponse(
                                                Socks4CommandStatus.SUCCESS, req.dstAddr(), 1234);
                                ctx.writeAndFlush(res);
                            } else if (msg instanceof LastHttpContent) {
                                final DefaultFullHttpResponse res = new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                                ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
                            }
                        }
                    });
        }
    }

    @RegisterExtension
    static NettyServerExtension proxyServer =
            new NettyServerExtension(20080, new SocksServerInitializer());

    @Test
    void dumbProxyClientTest() {
        final ClientFactory clientFactory = ClientFactory.builder().useProxy(true).build();
        final WebClient webClient = WebClient.builder("h1c://127.0.0.1:" + 1234)
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();
        assertThat(responseFuture).satisfies(CompletableFuture::isDone);
        final AggregatedHttpResponse response = responseFuture.join();
        assertThat(response.status()).isEqualByComparingTo(OK);
    }
}
