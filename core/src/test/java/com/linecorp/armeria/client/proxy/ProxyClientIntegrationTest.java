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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.DynamicBehaviorHandler;
import com.linecorp.armeria.internal.testing.NettyServerExtension;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
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
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ReferenceCountUtil;

class ProxyClientIntegrationTest {
    private static final String PROXY_PATH = "/proxy";
    private static final String SUCCESS_RESPONSE = "success";

    private static final DynamicBehaviorHandler DYNAMIC_HANDLER = new DynamicBehaviorHandler();

    @RegisterExtension
    @Order(0)
    static final SelfSignedCertificateExtension ssc = new SelfSignedCertificateExtension();

    @RegisterExtension
    @Order(1)
    static ServerExtension backendServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.port(0, SessionProtocol.HTTP);
            sb.port(0, SessionProtocol.HTTPS);
            sb.tlsSelfSigned();
            sb.service(PROXY_PATH, (ctx, req) -> HttpResponse.of(SUCCESS_RESPONSE));
        }
    };

    @RegisterExtension
    @Order(2)
    static NettyServerExtension socksProxyServer = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            ch.pipeline().addLast(new SocksPortUnificationServerHandler());
            ch.pipeline().addLast(DYNAMIC_HANDLER);
            ch.pipeline().addLast(new Socks4ProxyServerHandler());
            ch.pipeline().addLast(new Socks5ProxyServerHandler());
            ch.pipeline().addLast(new IntermediaryProxyServerHandler("socks"));
        }
    };

    @RegisterExtension
    @Order(3)
    static NettyServerExtension httpProxyServer = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            ch.pipeline().addLast(new HttpServerCodec());
            ch.pipeline().addLast(new HttpObjectAggregator(1024));
            ch.pipeline().addLast(new HttpProxyServerHandler());
            ch.pipeline().addLast(new IntermediaryProxyServerHandler("http"));
        }
    };

    @RegisterExtension
    @Order(4)
    static NettyServerExtension httpsProxyServer = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            final SslContext sslContext = SslContextBuilder
                    .forServer(ssc.privateKey(), ssc.certificate()).build();
            ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
            ch.pipeline().addLast(new HttpServerCodec());
            ch.pipeline().addLast(new HttpObjectAggregator(1024));
            ch.pipeline().addLast(new HttpProxyServerHandler());
            ch.pipeline().addLast(new SleepHandler());
            ch.pipeline().addLast(new IntermediaryProxyServerHandler("http"));
        }
    };

    @RegisterExtension
    @Order(5)
    static NettyServerExtension http1Server = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            ch.pipeline().addLast(new LoggingHandler(getClass()));
            ch.pipeline().addLast(new HttpServerCodec());
            ch.pipeline().addLast(new HttpObjectAggregator(1024));
            ch.pipeline().addLast(DYNAMIC_HANDLER);
        }
    };

    private static volatile int numSuccessfulProxyRequests;

    @BeforeEach
    void beforeEach() {
        numSuccessfulProxyRequests = 0;
        DYNAMIC_HANDLER.reset();
    }

    @Test
    void testDisabledProxyBasicCase() throws Exception {
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.direct())
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
        }
    }

    @Test
    void testSocks4BasicCase() throws Exception {
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.socks4(socksProxyServer.address()))
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
            assertThat(numSuccessfulProxyRequests).isEqualTo(1);
        }
    }

    @Test
    void testSocks5BasicCase() throws Exception {
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.socks5(socksProxyServer.address()))
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
            assertThat(numSuccessfulProxyRequests).isEqualTo(1);
        }
    }

    @Test
    void testH1CProxyBasicCase() throws Exception {
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.connect(httpProxyServer.address()))
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
            assertThat(numSuccessfulProxyRequests).isEqualTo(1);
        }
    }

    @Test
    void testWrappingSelectorBasicCase() throws Exception {
        final ProxySelector proxySelector = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return ImmutableList.of(new Proxy(Type.HTTP, httpProxyServer.address()));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        };
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(proxySelector)
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
            assertThat(numSuccessfulProxyRequests).isEqualTo(1);
        }
    }

    @Test
    void testSelectFailureFailsImmediately() throws Exception {
        final RuntimeException selectException = new RuntimeException("select exception");
        final TestProxyConfigSelector selector = new TestProxyConfigSelector(new ProxyConfigSelector() {
            @Override
            public ProxyConfig select(SessionProtocol protocol, Endpoint endpoint) {
                assertThat(protocol).isEqualTo(SessionProtocol.H1C);
                assertThat(endpoint).isEqualTo(backendServer.httpEndpoint());
                throw selectException;
            }

            @Override
            public void connectFailed(SessionProtocol protocol, Endpoint endpoint,
                                      SocketAddress sa, Throwable throwable) {
                fail("connectFailed should not be called");
            }
        });
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(selector)
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            assertThatThrownBy(responseFuture::join).isInstanceOf(CompletionException.class)
                                                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                                                    .hasRootCause(selectException);
        }
    }

    @Test
    void testNullProxyConfigFailsImmediately() throws Exception {
        final TestProxyConfigSelector selector = new TestProxyConfigSelector(new ProxyConfigSelector() {
            @Override
            public ProxyConfig select(SessionProtocol protocol, Endpoint endpoint) {
                assertThat(protocol).isEqualTo(SessionProtocol.H1C);
                assertThat(endpoint).isEqualTo(backendServer.httpEndpoint());
                return null;
            }

            @Override
            public void connectFailed(SessionProtocol protocol, Endpoint endpoint,
                                      SocketAddress sa, Throwable throwable) {
                fail("connectFailed should not be called");
            }
        });
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(selector)
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            assertThatThrownBy(responseFuture::join).isInstanceOf(CompletionException.class)
                                                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                                                    .hasRootCauseInstanceOf(NullPointerException.class)
                                                    .hasRootCauseMessage("proxyConfig");
        }
    }

    @Test
    void testHttpProxyUpgradeRequestFailure() throws Exception {
        DYNAMIC_HANDLER.setChannelReadCustomizer((ctx, msg) -> {
            final HttpRequest request = (HttpRequest) msg;
            final DefaultFullHttpResponse response;
            if ("h2c".equals(request.headers().get(HttpHeaderNames.UPGRADE))) {
                // reject http2 upgrade requests
                final HttpHeaders headers = new DefaultHttpHeaders().add(HttpHeaderNames.CONNECTION, "close");
                response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                       HttpResponseStatus.NOT_IMPLEMENTED,
                                                       Unpooled.EMPTY_BUFFER,
                                                       headers,
                                                       EmptyHttpHeaders.INSTANCE);
            } else {
                response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                       HttpResponseStatus.OK,
                                                       Unpooled.copiedBuffer(request.method().name(),
                                                                             StandardCharsets.US_ASCII));
            }

            ReferenceCountUtil.release(msg);
            ctx.writeAndFlush(response);
            ctx.close();
        });

        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.connect(httpProxyServer.address()))
                                  .useHttp2Preface(false)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.HTTP, http1Server.endpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo(HttpMethod.GET.name());
            assertThat(numSuccessfulProxyRequests).isEqualTo(2);
        }
    }

    @Test
    void testHttpProxyPrefaceFailure() throws Exception {
        DYNAMIC_HANDLER.setChannelReadCustomizer((ctx, msg) -> {
            final HttpRequest request = (HttpRequest) msg;
            final DefaultFullHttpResponse response;
            if (HttpMethod.valueOf("PRI").equals(request.method())) {
                // reject http2 preface
                final HttpHeaders headers = new DefaultHttpHeaders().add(HttpHeaderNames.CONNECTION, "close");
                response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                       HttpResponseStatus.NOT_IMPLEMENTED,
                                                       Unpooled.EMPTY_BUFFER,
                                                       headers,
                                                       EmptyHttpHeaders.INSTANCE);
            } else {
                response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                       HttpResponseStatus.OK,
                                                       Unpooled.copiedBuffer(request.method().name(),
                                                                             StandardCharsets.US_ASCII));
            }

            ReferenceCountUtil.release(msg);
            ctx.writeAndFlush(response);
            ctx.close();
        });

        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.connect(httpProxyServer.address()))
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.HTTP, http1Server.endpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo(HttpMethod.GET.name());
            assertThat(numSuccessfulProxyRequests).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @MethodSource("sessionAndEndpointProvider")
    void testHttpsProxy(SessionProtocol protocol, Endpoint endpoint) throws Exception {
        final ClientFactory clientFactory =
                ClientFactory.builder().tlsNoVerify().proxyConfig(
                        ProxyConfig.connect(httpsProxyServer.address(), true)).build();
        final WebClient webClient = WebClient.builder(protocol, endpoint)
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();
        final AggregatedHttpResponse response = responseFuture.join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
        assertThat(numSuccessfulProxyRequests).isEqualTo(1);
        clientFactory.close();
    }

    @Test
    void testProxyWithH2C() throws Exception {
        final int numRequests = 5;
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.socks4(socksProxyServer.address()))
                                  .useHttp2Preface(true)
                                  .build()) {

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
            assertThat(numSuccessfulProxyRequests).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void testProxyWithUserName() throws Exception {
        final String username = "username";
        DYNAMIC_HANDLER.setChannelReadCustomizer((ctx, msg) -> {
            if (msg instanceof DefaultSocks4CommandRequest) {
                assertThat(username).isEqualTo(((DefaultSocks4CommandRequest) msg).userId());
            }
            ctx.fireChannelRead(msg);
        });
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.socks4(socksProxyServer.address(), username))
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
            assertThat(numSuccessfulProxyRequests).isEqualTo(1);
        }
    }

    @Test
    void testProxy_connectionFailure_throwsException() throws Exception {
        final int unusedPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            unusedPort = ss.getLocalPort();
        }

        final AtomicInteger failedAttempts = new AtomicInteger();
        final InetSocketAddress proxyAddress = new InetSocketAddress("127.0.0.1", unusedPort);
        final TestProxyConfigSelector selector = new TestProxyConfigSelector(new ProxyConfigSelector() {
            @Override
            public ProxyConfig select(SessionProtocol protocol, Endpoint endpoint) {
                assertThat(protocol).isEqualTo(SessionProtocol.H1C);
                assertThat(endpoint).isEqualTo(backendServer.httpEndpoint());
                return ProxyConfig.socks4(proxyAddress);
            }

            @Override
            public void connectFailed(SessionProtocol protocol, Endpoint endpoint,
                                      SocketAddress sa, Throwable throwable) {
                assertThat(protocol).isEqualTo(SessionProtocol.H1C);
                assertThat(endpoint).isEqualTo(backendServer.httpEndpoint());
                assertThat(sa).isEqualTo(proxyAddress);
                assertThat(throwable).isInstanceOf(UnprocessedRequestException.class)
                                     .hasCauseInstanceOf(ConnectException.class);
                failedAttempts.incrementAndGet();
            }
        });
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(selector)
                                  .useHttp2Preface(true)
                                  .build()) {

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
            assertThat(failedAttempts).hasValue(1);
            assertThat(selector.result()).isTrue();
        }
    }

    @Test
    void testProxy_connectionTimeoutFailure_throwsException() throws Exception {
        DYNAMIC_HANDLER.setChannelReadCustomizer((ctx, msg) -> {
            if (msg instanceof DefaultSocks4CommandRequest) {
                ctx.channel().eventLoop().schedule(
                        () -> ctx.fireChannelRead(msg), 50, TimeUnit.MILLISECONDS);
            } else {
                ctx.fireChannelRead(msg);
            }
        });

        final AtomicInteger failedAttempts = new AtomicInteger();
        final InetSocketAddress proxyAddress = socksProxyServer.address();
        final TestProxyConfigSelector selector = new TestProxyConfigSelector(new ProxyConfigSelector() {
            @Override
            public ProxyConfig select(SessionProtocol protocol, Endpoint endpoint) {
                assertThat(protocol).isEqualTo(SessionProtocol.H1C);
                assertThat(endpoint).isEqualTo(backendServer.httpEndpoint());
                return ProxyConfig.socks4(proxyAddress);
            }

            @Override
            public void connectFailed(SessionProtocol protocol, Endpoint endpoint,
                                      SocketAddress sa, Throwable throwable) {
                assertThat(protocol).isEqualTo(SessionProtocol.H1C);
                assertThat(endpoint).isEqualTo(backendServer.httpEndpoint());
                assertThat(sa).isEqualTo(proxyAddress);
                assertThat(throwable).isInstanceOf(UnprocessedRequestException.class)
                                     .hasCauseInstanceOf(ProxyConnectException.class);
                failedAttempts.incrementAndGet();
            }
        });

        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(selector)
                                  .connectTimeoutMillis(1)
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            assertThatThrownBy(responseFuture::join).isInstanceOf(CompletionException.class)
                                                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                                                    .hasRootCauseInstanceOf(ProxyConnectException.class);
            assertThat(failedAttempts).hasValue(1);
            assertThat(selector.result()).isTrue();
        }
    }

    @Test
    void testProxy_serverImmediateClose_throwsException() throws Exception {
        DYNAMIC_HANDLER.setChannelReadCustomizer((ctx, msg) -> {
            ReferenceCountUtil.release(msg);
            ctx.close();
        });
        final TestProxyConfigSelector selector = new TestProxyConfigSelector(new ProxyConfigSelector() {
            @Override
            public ProxyConfig select(SessionProtocol protocol, Endpoint endpoint) {
                return ProxyConfig.socks4(socksProxyServer.address());
            }

            @Override
            public void connectFailed(SessionProtocol protocol, Endpoint endpoint,
                                      SocketAddress sa, Throwable throwable) {
                assertThat(protocol).isEqualTo(SessionProtocol.H1C);
                assertThat(endpoint).isEqualTo(backendServer.httpEndpoint());
                assertThat(sa).isEqualTo(socksProxyServer.address());
                assertThat(throwable).isInstanceOf(UnprocessedRequestException.class)
                                     .hasCauseInstanceOf(ProxyConnectException.class);
                throw new RuntimeException("connectFailed exception");
            }
        });

        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(selector)
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            assertThatThrownBy(responseFuture::join).isInstanceOf(CompletionException.class)
                                                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                                                    .hasRootCauseInstanceOf(ProxyConnectException.class);
            assertThat(selector.result()).isTrue();
        }
    }

    @Test
    void testProxy_responseFailure_throwsException() throws Exception {
        DYNAMIC_HANDLER.setWriteCustomizer((ctx, msg, promise) -> {
            ReferenceCountUtil.release(msg);
            ctx.write(new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED), promise);
        });

        final AtomicInteger failedAttempts = new AtomicInteger();
        final InetSocketAddress proxyAddress = socksProxyServer.address();
        final TestProxyConfigSelector selector = new TestProxyConfigSelector(new ProxyConfigSelector() {
            @Override
            public ProxyConfig select(SessionProtocol protocol, Endpoint endpoint) {
                assertThat(protocol).isEqualTo(SessionProtocol.H1C);
                assertThat(endpoint).isEqualTo(backendServer.httpEndpoint());
                return ProxyConfig.socks4(proxyAddress);
            }

            @Override
            public void connectFailed(SessionProtocol protocol, Endpoint endpoint,
                                      SocketAddress sa, Throwable throwable) {
                assertThat(protocol).isEqualTo(SessionProtocol.H1C);
                assertThat(endpoint).isEqualTo(backendServer.httpEndpoint());
                assertThat(sa).isEqualTo(proxyAddress);
                assertThat(throwable).isInstanceOf(UnprocessedRequestException.class)
                                     .hasCauseInstanceOf(ProxyConnectException.class);
                failedAttempts.incrementAndGet();
            }
        });

        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(selector)
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();

            assertThatThrownBy(responseFuture::join).isInstanceOf(CompletionException.class)
                                                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                                                    .hasRootCauseInstanceOf(ProxyConnectException.class);
            assertThat(failedAttempts).hasValue(1);
            assertThat(selector.result()).isTrue();
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

    private static final class SleepHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof ProxySuccessEvent) {
                // Sleep as much as defaultWriteTimeoutMillis in order to make sure that the
                // first writing to the channel occurs after ProxySuccessEvent is triggered.
                // If the first writing happens before ProxySuccessEvent is triggered,
                // the client would get WriteTimeoutException that makes the test fail.
                Thread.sleep(Flags.defaultWriteTimeoutMillis());
            }
            super.userEventTriggered(ctx, evt);
        }
    }

    private static final class IntermediaryProxyServerHandler extends ChannelInboundHandlerAdapter {
        private final ConcurrentLinkedDeque<ByteBuf> received = new ConcurrentLinkedDeque<>();
        @Nullable
        private Channel backend;
        private final String proxyType;

        private IntermediaryProxyServerHandler(String proxyType) {
            this.proxyType = proxyType;
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof ProxySuccessEvent) {
                connectBackend(ctx, ((ProxySuccessEvent) evt).getBackendAddress()).addListener(f -> {
                    if (f.isSuccess()) {
                        numSuccessfulProxyRequests++;
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

    static Stream<Arguments> sessionAndEndpointProvider() {
        return Stream.of(
                Arguments.arguments(SessionProtocol.HTTP, backendServer.httpEndpoint()),
                Arguments.arguments(SessionProtocol.H1C, backendServer.httpEndpoint()),
                Arguments.arguments(SessionProtocol.H2C, backendServer.httpEndpoint()),
                Arguments.arguments(SessionProtocol.H1, backendServer.httpsEndpoint()),
                Arguments.arguments(SessionProtocol.H2, backendServer.httpsEndpoint()),
                Arguments.arguments(SessionProtocol.HTTPS, backendServer.httpsEndpoint())
        );
    }

    /**
     * This test class ensures the test fails when an assertion in the callback fails.
     */
    private static class TestProxyConfigSelector implements ProxyConfigSelector {
        final ProxyConfigSelector inner;
        final AtomicBoolean result;

        TestProxyConfigSelector(ProxyConfigSelector inner) {
            this.inner = inner;
            result = new AtomicBoolean(true);
        }

        boolean result() {
            return result.get();
        }

        @Override
        public ProxyConfig select(SessionProtocol protocol, Endpoint endpoint) {
            try {
                return inner.select(protocol, endpoint);
            } catch (AssertionError e) {
                result.set(false);
                throw e;
            }
        }

        @Override
        public void connectFailed(SessionProtocol protocol, Endpoint endpoint,
                                  SocketAddress sa, Throwable throwable) {
            try {
                inner.connectFailed(protocol, endpoint, sa, throwable);
            } catch (AssertionError e) {
                result.set(false);
                throw e;
            }
        }
    }
}
