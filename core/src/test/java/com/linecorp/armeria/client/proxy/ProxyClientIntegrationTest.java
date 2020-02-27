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

import static com.google.common.base.Preconditions.checkArgument;
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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandRequest;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandResponse;
import io.netty.handler.codec.socksx.v4.Socks4ClientDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ClientEncoder;
import io.netty.handler.codec.socksx.v4.Socks4CommandStatus;
import io.netty.handler.codec.socksx.v4.Socks4CommandType;
import io.netty.handler.codec.socksx.v4.Socks4Message;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5Message;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.util.ReferenceCountUtil;

public class ProxyClientIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ProxyClientIntegrationTest.class);
    private static final String PROXY_PATH = "/proxy";
    private static final InetSocketAddress SOCKS_PROXY_ADDRESS = new InetSocketAddress("127.0.0.1", 20080);
    private static final InetSocketAddress HTTP_PROXY_ADDRESS = new InetSocketAddress("127.0.0.1", 20081);
    private static final InetSocketAddress BACKEND_ADDRESS = new InetSocketAddress("127.0.0.1", 1234);

    private static final class SocksProxyServerInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        public void initChannel(SocketChannel ch) throws Exception {
            ch.pipeline().addLast(
                    new LoggingHandler(getClass()),
                    new SocksPortUnificationServerHandler(),
                    new CustomSocks4ProxyServerHandler(),
                    new CustomSocks5ProxyServerHandler(),
                    new CustomBaseProxyServerHandler("socks"));
        }
    }

    private static final class HttpProxyServerInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        public void initChannel(SocketChannel ch) throws Exception {
            ch.pipeline().addLast(new LoggingHandler(getClass()));
            ch.pipeline().addLast(new HttpServerCodec());
            ch.pipeline().addLast(new HttpObjectAggregator(1024));
            ch.pipeline().addLast(new CustomHttpProxyServerHandler());
            ch.pipeline().addLast(new CustomBaseProxyServerHandler("http"));
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
    static NettyServerExtension socksProxyServer =
            new NettyServerExtension(SOCKS_PROXY_ADDRESS, new SocksProxyServerInitializer());

    @RegisterExtension
    @Order(3)
    static NettyServerExtension httpProxyServer =
            new NettyServerExtension(HTTP_PROXY_ADDRESS, new HttpProxyServerInitializer());

    @Test
    void testSocks4BasicCase() throws Exception {
        final ClientFactory clientFactory =
                ClientFactory.builder()
                             .proxyHandler(new Socks4ProxyHandler(SOCKS_PROXY_ADDRESS))
                             .build();
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
    void testSocks5BasicCase() throws Exception {
        final ClientFactory clientFactory =
                ClientFactory.builder()
                             .proxyHandler(new Socks5ProxyHandler(SOCKS_PROXY_ADDRESS))
                             .build();
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
    void testHttpProxyBasicCase() throws Exception {
        final ClientFactory clientFactory =
                ClientFactory.builder()
                             .proxyHandler(new HttpProxyHandler(HTTP_PROXY_ADDRESS))
                             .build();
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
    void testServerUsingNettyClient() throws Exception {
        // TODO: this is for testing the socks netty server. remove before merging.
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
                ch.pipeline().addLast(new CustomSocks4ProxyClientHandler());
                ch.pipeline().addLast(new CustomHttpClientHandler(status::set));
            }
        });
        b.connect(SOCKS_PROXY_ADDRESS);

        await().untilAtomic(status, equalTo(HttpResponseStatus.OK));
    }

    private static class CustomSocks4ProxyClientHandler extends ChannelInboundHandlerAdapter {
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

    static class ProxySuccessEvent {
        private final InetSocketAddress backendAddress;
        private final Object response;

        ProxySuccessEvent(InetSocketAddress backendAddress, Object response) {
            this.backendAddress = backendAddress;
            this.response = response;
        }

        public Object getResponse() {
            return response;
        }

        public InetSocketAddress getBackendAddress() {
            return backendAddress;
        }
    }

    private static class CustomSocks5ProxyServerHandler extends SimpleChannelInboundHandler<Socks5Message> {
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

    private static class CustomSocks4ProxyServerHandler extends SimpleChannelInboundHandler<Socks4Message> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Socks4Message msg) throws Exception {
            if (msg instanceof DefaultSocks4CommandRequest) {
                final DefaultSocks4CommandRequest req = (DefaultSocks4CommandRequest) msg;
                ctx.fireUserEventTriggered(new ProxySuccessEvent(
                        new InetSocketAddress(req.dstAddr(), req.dstPort()),
                        new DefaultSocks4CommandResponse(Socks4CommandStatus.SUCCESS)));
            } else {
                throw new IllegalStateException("unexpected msg: " + msg);
            }
        }
    }

    private static class CustomHttpProxyServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
            final String uri = msg.uri();
            final String[] split = uri.split(":");
            checkArgument(split.length == 2, "invalid destination url");

            ctx.fireUserEventTriggered(new ProxySuccessEvent(
                    new InetSocketAddress(split[0], Integer.parseInt(split[1])),
                    new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)));
        }
    }

    private static final class CustomBaseProxyServerHandler extends ChannelInboundHandlerAdapter {
        private InetSocketAddress backendAddress;
        private final ConcurrentLinkedDeque<ByteBuf> received = new ConcurrentLinkedDeque<>();
        private Channel backend;
        private final String proxyType;

        private CustomBaseProxyServerHandler(String proxyType) {
            this.proxyType = proxyType;
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof ProxySuccessEvent) {
                backendAddress = ((ProxySuccessEvent) evt).getBackendAddress();
                connectBackend(ctx).addListener(f -> {
                    if (f.isSuccess()) {
                        ctx.writeAndFlush(((ProxySuccessEvent) evt).getResponse());

                        if ("http".equals(proxyType)) {
                            ctx.pipeline().remove(HttpObjectAggregator.class);
                            ctx.pipeline().remove(HttpServerCodec.class);
                        }
                    } else {
                        ctx.close();
                    }
                });
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ByteBuf) {
                final ByteBuf backendMessage = ReferenceCountUtil.retain((ByteBuf) msg);
                received.add(backendMessage);
                flush();
            } else {
                throw new IllegalStateException("unexpected msg: " + msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
                throws Exception {
            backend.close();
        }

        private ChannelFuture connectBackend(final ChannelHandlerContext ctx) {
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
            return b.connect(backendAddress).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    clientCtx.close();
                    return;
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
