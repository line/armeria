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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Proxy;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.DynamicBehaviorHandler;
import com.linecorp.armeria.internal.testing.NettyServerExtension;
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
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandRequest;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandResponse;
import io.netty.handler.codec.socksx.v4.Socks4CommandStatus;
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
import io.netty.util.ReferenceCountUtil;

public class ProxyClientIntegrationTest {
    private static final String PROXY_PATH = "/proxy";
    private static final String SUCCESS_RESPONSE = "success";

    private static final DynamicBehaviorHandler SOCKS_DYNAMIC_HANDLER = new DynamicBehaviorHandler();

    @RegisterExtension
    @Order(1)
    static ServerExtension backendServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.port(0, SessionProtocol.HTTP);
            sb.service(PROXY_PATH, (ctx, req) -> HttpResponse.of(SUCCESS_RESPONSE));
        }
    };

    @RegisterExtension
    @Order(2)
    static NettyServerExtension socksProxyServer = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) {
            ch.pipeline().addLast(new LoggingHandler(getClass()));
            ch.pipeline().addLast(new SocksPortUnificationServerHandler());
            ch.pipeline().addLast(SOCKS_DYNAMIC_HANDLER);
            ch.pipeline().addLast(new Socks4ProxyServerHandler());
            ch.pipeline().addLast(new Socks5ProxyServerHandler());
            ch.pipeline().addLast(new IntermediaryProxyServerHandler("socks"));
        }
    };

    @RegisterExtension
    @Order(3)
    static NettyServerExtension httpProxyServer = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) {
            ch.pipeline().addLast(new LoggingHandler(getClass()));
            ch.pipeline().addLast(new HttpServerCodec());
            ch.pipeline().addLast(new HttpObjectAggregator(1024));
            ch.pipeline().addLast(new HttpProxyServerHandler());
            ch.pipeline().addLast(new IntermediaryProxyServerHandler("http"));
        }
    };

    @BeforeEach
    void beforeEach() {
        SOCKS_DYNAMIC_HANDLER.reset();
    }

    @Test
    void testSocks4BasicCase() throws Exception {
        final ClientFactory clientFactory = ClientFactory.builder().proxy(
                Proxy.socks4(socksProxyServer.address())).build();
        final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();
        final AggregatedHttpResponse response = responseFuture.join();

        assertThat(response.status()).isEqualByComparingTo(OK);
        assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
    }

    @Test
    void testSocks5BasicCase() throws Exception {
        final ClientFactory clientFactory = ClientFactory.builder().proxy(
                Proxy.socks5(socksProxyServer.address())).build();
        final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();
        final AggregatedHttpResponse response = responseFuture.join();
        assertThat(response.status()).isEqualByComparingTo(OK);
        assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
    }

    @Test
    void testHttpProxyBasicCase() throws Exception {
        final ClientFactory clientFactory = ClientFactory.builder().proxy(
                Proxy.connect(httpProxyServer.address())).build();
        final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();
        final AggregatedHttpResponse response = responseFuture.join();
        assertThat(response.status()).isEqualByComparingTo(OK);
        assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
    }

    @Test
    void testProxyWithH2C() throws Exception {
        final int numRequests = 5;
        final ClientFactory clientFactory = ClientFactory.builder().proxy(
                Proxy.socks4(socksProxyServer.address())).build();
        final WebClient webClient = WebClient.builder(SessionProtocol.H2C, backendServer.httpEndpoint())
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();

        final List<CompletableFuture<AggregatedHttpResponse>> responseFutures = new ArrayList<>();
        for (int i = 0; i < numRequests; i++) {
            responseFutures.add(webClient.get(PROXY_PATH).aggregate());
        }
        await().until(() -> responseFutures.stream().allMatch(CompletableFuture::isDone));
        assertThat(responseFutures.stream().map(CompletableFuture::join))
                .allMatch(response -> response.contentUtf8().equals(SUCCESS_RESPONSE));
    }

    @Test
    void testProxy_protocolUpgrade_notSharableExceptionNotThrown() throws Exception {
        SOCKS_DYNAMIC_HANDLER.setWriteCustomizer((ctx, msg, promise) -> {
            ctx.write(new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED), promise);
        });
        final ClientFactory clientFactory = ClientFactory.builder().proxy(
                Proxy.socks4(socksProxyServer.address())).build();
        final WebClient webClient = WebClient.builder(SessionProtocol.HTTP, backendServer.httpEndpoint())
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();
        assertThatThrownBy(responseFuture::join).isInstanceOf(CompletionException.class)
                                                .hasCauseInstanceOf(ClosedSessionException.class);
    }

    @Test
    void testProxy_connectionFailure_throwsException() throws Exception {
        final int unusedPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            unusedPort = ss.getLocalPort();
        }

        final ClientFactory clientFactory = ClientFactory.builder().proxy(
                Proxy.socks4(new InetSocketAddress("127.0.0.1", unusedPort))).build();
        final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();

        assertThatThrownBy(responseFuture::join).isInstanceOf(CompletionException.class)
                                                .hasMessageContaining("Connection refused")
                                                .hasCauseInstanceOf(UnprocessedRequestException.class)
                                                .hasRootCauseInstanceOf(ConnectException.class);
    }

    @Test
    void testProxy_connectionTimeoutFailure_throwsException() throws Exception {
        SOCKS_DYNAMIC_HANDLER.setChannelReadCustomizer((ctx, msg) -> {
            if (msg instanceof DefaultSocks4CommandRequest) {
                Thread.sleep(50);
            }
            ctx.fireChannelRead(msg);
        });

        final ClientFactory clientFactory = ClientFactory.builder().proxy(
                Proxy.socks4(socksProxyServer.address(), 1)).build();

        final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();

        assertThatThrownBy(responseFuture::join).isInstanceOf(CompletionException.class)
                                                .hasCauseInstanceOf(ClosedSessionException.class);
    }

    @Test
    void testProxy_responseFailure_throwsException() throws Exception {
        SOCKS_DYNAMIC_HANDLER.setWriteCustomizer((ctx, msg, promise) -> {
            ctx.write(new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED), promise);
        });
        final ClientFactory clientFactory = ClientFactory.builder().proxy(
                Proxy.socks4(socksProxyServer.address())).build();
        final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();

        assertThatThrownBy(responseFuture::join).isInstanceOf(CompletionException.class)
                                                .hasCauseInstanceOf(ClosedSessionException.class);
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

    private static class Socks5ProxyServerHandler extends SimpleChannelInboundHandler<Socks5Message> {
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

    private static class Socks4ProxyServerHandler extends SimpleChannelInboundHandler<Socks4Message> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Socks4Message msg) throws Exception {
            if (msg instanceof DefaultSocks4CommandRequest) {
                final DefaultSocks4CommandRequest req = (DefaultSocks4CommandRequest) msg;
                final DefaultSocks4CommandResponse response;
                response = new DefaultSocks4CommandResponse(Socks4CommandStatus.SUCCESS);
                ctx.fireUserEventTriggered(new ProxySuccessEvent(
                        new InetSocketAddress(req.dstAddr(), req.dstPort()), response));
            } else {
                throw new IllegalStateException("unexpected msg: " + msg);
            }
        }
    }

    private static class HttpProxyServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
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

    private static final class IntermediaryProxyServerHandler extends ChannelInboundHandlerAdapter {
        private InetSocketAddress backendAddress;
        private final ConcurrentLinkedDeque<ByteBuf> received = new ConcurrentLinkedDeque<>();
        private Channel backend;
        private final String proxyType;

        private IntermediaryProxyServerHandler(String proxyType) {
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
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (backend != null) {
                backend.close();
            }
            super.channelInactive(ctx);
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
