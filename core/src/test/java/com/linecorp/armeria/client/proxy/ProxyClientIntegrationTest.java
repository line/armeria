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
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandRequest;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandResponse;
import io.netty.handler.codec.socksx.v4.Socks4ClientDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ClientEncoder;
import io.netty.handler.codec.socksx.v4.Socks4CommandStatus;
import io.netty.handler.codec.socksx.v4.Socks4CommandType;
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.ReferenceCountUtil;

public class ProxyClientIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ProxyClientIntegrationTest.class);
    private static final String PROXY_PATH = "/proxy";
    private static final InetSocketAddress PROXY_ADDRESS = new InetSocketAddress("127.0.0.1", 20080);
    private static final InetSocketAddress BACKEND_ADDRESS = new InetSocketAddress("127.0.0.1", 1234);

    private static final class SocksServerInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        public void initChannel(SocketChannel ch) throws Exception {
            ch.pipeline().addLast(
                    new Socks4ServerDecoder(),
                    Socks4ServerEncoder.INSTANCE,
                    new LoggingHandler(getClass()),
                    new CustomSocksProxyServerHandler());
        }
    }

    @RegisterExtension
    @Order(1)
    static ServerExtension backendServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.port(BACKEND_ADDRESS.getPort(), SessionProtocol.HTTP);
            sb.service(PROXY_PATH, (ctx, req) -> {
                logger.info("received request {}", req);
                return HttpResponse.of(200);
            });
        }
    };

    @RegisterExtension
    @Order(2)
    static NettyServerExtension proxyServer =
            new NettyServerExtension(PROXY_ADDRESS.getPort(), new SocksServerInitializer());

    @Test
    void dumbProxyClientTest() throws Exception {
        final ClientFactory clientFactory = ClientFactory.builder().useProxy(true).build();
        final Endpoint endpoint = Endpoint.of(BACKEND_ADDRESS.getHostString(), BACKEND_ADDRESS.getPort());
        final WebClient webClient = WebClient.builder(SessionProtocol.H1C, endpoint)
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();
        assertThat(responseFuture).satisfies(CompletableFuture::isDone);
        final AggregatedHttpResponse response = responseFuture.join();
        assertThat(response.status()).isEqualByComparingTo(OK);
    }

    @Test
    void testNettyServerWithArmeriaBacking() throws Exception {
        final Bootstrap b = new Bootstrap();
        b.group(new NioEventLoopGroup(1));
        b.channel(NioSocketChannel.class);

        final AtomicReference<HttpResponseStatus> status = new AtomicReference<>();
        b.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline().addLast(new LoggingHandler(getClass()));
                ch.pipeline().addLast(Socks4ClientEncoder.INSTANCE);
                ch.pipeline().addLast(new Socks4ClientDecoder());
                ch.pipeline().addLast(new CustomSocksProxyClientHandler());
                ch.pipeline().addLast(new CustomHttpClientHandler(status::set));
            }
        });
        b.connect(PROXY_ADDRESS);

        await().untilAtomic(status, equalTo(HttpResponseStatus.OK));
    }

    private static class CustomSocksProxyClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            ctx.writeAndFlush(new DefaultSocks4CommandRequest(
                    Socks4CommandType.CONNECT, BACKEND_ADDRESS.getHostString(),
                    BACKEND_ADDRESS.getPort()));
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof DefaultSocks4CommandResponse) {
                final DefaultSocks4CommandResponse response = (DefaultSocks4CommandResponse) msg;
                if (response.status() != Socks4CommandStatus.SUCCESS) {
                    throw new RuntimeException("socks protocol negotiation failed");
                }

                ctx.pipeline().remove(Socks4ClientEncoder.class);
                ctx.pipeline().remove(Socks4ClientDecoder.class);
                ctx.pipeline().addBefore(ctx.name(), HttpClientCodec.class.getName(),
                                         new HttpClientCodec());
                ctx.writeAndFlush(new DefaultHttpRequest(
                        HttpVersion.HTTP_1_0, HttpMethod.GET, PROXY_PATH));
            } else {
                ctx.fireChannelRead(msg);
            }
        }
    }

    private static class CustomHttpClientHandler extends ChannelInboundHandlerAdapter {
        final Consumer<HttpResponseStatus> statusConsumer;

        CustomHttpClientHandler(Consumer<HttpResponseStatus> statusConsumer) {
            this.statusConsumer = statusConsumer;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof io.netty.handler.codec.http.HttpResponse) {
                if (((io.netty.handler.codec.http.HttpResponse) msg).status() != HttpResponseStatus.OK) {
                    throw new RuntimeException("response has failed");
                }
                statusConsumer.accept(((io.netty.handler.codec.http.HttpResponse) msg).status());
                ctx.close();
            } else {
                ctx.fireChannelRead(msg);
            }
        }
    }

    private static class CustomSocksProxyServerHandler extends ChannelInboundHandlerAdapter {
        private InetSocketAddress backendAddress;
        private boolean finished;
        private final ConcurrentLinkedDeque<ByteBuf> received = new ConcurrentLinkedDeque<>();
        private Channel backend;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof DefaultSocks4CommandRequest) {
                if (finished) {
                    return;
                }
                finished = true;

                final DefaultSocks4CommandRequest req = (DefaultSocks4CommandRequest) msg;
                backendAddress = new InetSocketAddress(req.dstAddr(), req.dstPort());

                final DefaultSocks4CommandResponse res =
                        new DefaultSocks4CommandResponse(Socks4CommandStatus.SUCCESS);
                ctx.writeAndFlush(res);
                ctx.pipeline().remove(Socks4ServerDecoder.class);
                ctx.pipeline().remove(Socks4ServerEncoder.class);

                connectBackend(ctx);
            } else if (msg instanceof ByteBuf) {
                final ByteBuf backendMessage = ReferenceCountUtil.retain((ByteBuf) msg);
                received.add(backendMessage);
                flush();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
                throws Exception {
            backend.close();
        }

        private void connectBackend(final ChannelHandlerContext ctx) {
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
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
                                throws Exception {
                            clientCtx.close();
                        }
                    });
                }
            });
            b.connect(backendAddress).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    clientCtx.close();
                }
                backend = f.channel();
                flush();
            });
        }

        private void flush() {
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
}
