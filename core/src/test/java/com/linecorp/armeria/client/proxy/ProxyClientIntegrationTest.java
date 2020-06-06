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
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_IMPLEMENTED;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.extension.ConditionEvaluationResult.disabled;
import static org.junit.jupiter.api.extension.ConditionEvaluationResult.enabled;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.DynamicBehaviorHandler;
import com.linecorp.armeria.internal.testing.NettyServerExtension;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.SelfSignedCertificateExtension;
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

public class ProxyClientIntegrationTest {
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
            sb.service(PROXY_PATH, (ctx, req) -> HttpResponse.of(SUCCESS_RESPONSE));
        }
    };

    @RegisterExtension
    @Order(2)
    static NettyServerExtension socksProxyServer = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            ch.pipeline().addLast(new LoggingHandler(getClass()));
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
            ch.pipeline().addLast(new LoggingHandler(getClass()));
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
            ch.pipeline().addLast(new LoggingHandler(getClass()));
            ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
            ch.pipeline().addLast(new LoggingHandler(getClass()));
            ch.pipeline().addLast(new HttpServerCodec());
            ch.pipeline().addLast(new HttpObjectAggregator(1024));
            ch.pipeline().addLast(new HttpProxyServerHandler());
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
        try (ClientFactory clientFactory = ClientFactory.builder().proxyConfig(ProxyConfig.direct()).build()) {
            final WebClient webClient = WebClient.builder(H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();

            assertThat(response.status()).isEqualTo(OK);
            assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
        }
    }

    @Test
    void testNullDefaultSelector() throws Exception {
        final ProxySelector defaultProxySelector = ProxySelector.getDefault();
        ProxySelector.setDefault(null);
        try (ClientFactory clientFactory = ClientFactory.builder().build()) {
            final WebClient webClient = WebClient.builder(H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory).build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(OK);
            assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
            assertThat(numSuccessfulProxyRequests).isEqualTo(0);
        } finally {
            ProxySelector.setDefault(defaultProxySelector);
        }
    }

    @Test
    void testSocks4BasicCase() throws Exception {
        try (ClientFactory clientFactory = ClientFactory.builder().proxyConfig(
                ProxyConfig.socks4(socksProxyServer.address())).build()) {
            final WebClient webClient = WebClient.builder(H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();

            assertThat(response.status()).isEqualTo(OK);
            assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
            assertThat(numSuccessfulProxyRequests).isEqualTo(1);
        }
    }

    @Test
    void testSocks5BasicCase() throws Exception {
        try (ClientFactory clientFactory = ClientFactory.builder().proxyConfig(
                ProxyConfig.socks5(socksProxyServer.address())).build()) {
            final WebClient webClient = WebClient.builder(H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(OK);
            assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
            assertThat(numSuccessfulProxyRequests).isEqualTo(1);
        }
    }

    @Test
    void testH1CProxyBasicCase() throws Exception {
        try (ClientFactory clientFactory = ClientFactory.builder().proxyConfig(
                ProxyConfig.connect(httpProxyServer.address())).build()) {
            final WebClient webClient = WebClient.builder(H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(OK);
            assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
            assertThat(numSuccessfulProxyRequests).isEqualTo(1);
        }
    }

    @Test
    void testSelectFailureFallsBackToDirect() throws Exception {
        final ProxyConfigSelector selector = new ProxyConfigSelector() {
            @Override
            public ProxyConfig select(URI uri) {
                throw new RuntimeException("select exception");
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, Throwable throwable) {
                fail("connectFailed should not be called");
            }
        };
        try (ClientFactory clientFactory = ClientFactory.builder().proxyConfig(selector).build()) {
            final WebClient webClient = WebClient.builder(H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(OK);
            assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
            assertThat(numSuccessfulProxyRequests).isEqualTo(0);
        }
    }

    @Test
    void testNullProxyConfigFallsBackToDirect() throws Exception {
        final ProxyConfigSelector selector = new ProxyConfigSelector() {
            @Override
            public ProxyConfig select(URI uri) {
                return null;
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, Throwable throwable) {
                fail("connectFailed should not be called");
            }
        };
        try (ClientFactory clientFactory = ClientFactory.builder().proxyConfig(selector).build()) {
            final WebClient webClient = WebClient.builder(H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(OK);
            assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
            assertThat(numSuccessfulProxyRequests).isEqualTo(0);
        }
    }

    @Test
    void testConnectFailedExceptionNotPropagated() throws Exception {
        DYNAMIC_HANDLER.setChannelReadCustomizer((ctx, msg) -> {
            ctx.close();
        });
        final ProxyConfigSelector selector = new ProxyConfigSelector() {
            @Override
            public ProxyConfig select(URI uri) {
                return ProxyConfig.socks4(socksProxyServer.address());
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, Throwable throwable) {
                throw new RuntimeException("connectFailed exception");
            }
        };
        try (ClientFactory clientFactory = ClientFactory.builder().proxyConfig(selector).build()) {
            final WebClient webClient = WebClient.builder(H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            assertThatThrownBy(responseFuture::join).isInstanceOf(CompletionException.class)
                                                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                                                    .hasRootCauseInstanceOf(ProxyConnectException.class);
        }
    }

    @Test
    @EnableIfDefaultSelector
    void testHttpProxyByDefaultSelector() throws Exception {
        final String httpNonProxyHosts = System.getProperty("http.nonProxyHosts");
        try (ClientFactory clientFactory = ClientFactory.builder().proxyConfig(ProxySelector.getDefault())
                                                        .build()) {
            System.setProperty("http.proxyHost", httpProxyServer.address().getHostString());
            System.setProperty("http.proxyPort", String.valueOf(httpProxyServer.address().getPort()));
            System.setProperty("http.nonProxyHosts", "");

            final WebClient webClient = WebClient.builder(H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(OK);
            assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
            assertThat(numSuccessfulProxyRequests).isEqualTo(1);
        } finally {
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            if (httpNonProxyHosts != null) {
                System.setProperty("http.nonProxyHosts", httpNonProxyHosts);
            }
        }
    }

    @Test
    void testHttpProxyUpgradeRequestFailure() throws Exception {
        DYNAMIC_HANDLER.setChannelReadCustomizer((ctx, msg) -> {
            if (!(msg instanceof FullHttpRequest)) {
                ctx.close();
            }
            final HttpRequest request = (HttpRequest) msg;
            final DefaultFullHttpResponse response;
            if ("h2c".equals(request.headers().get(HttpHeaderNames.UPGRADE))) {
                // reject http2 upgrade requests
                final HttpHeaders headers = new DefaultHttpHeaders().add(CONNECTION, "close");
                response = new DefaultFullHttpResponse(HTTP_1_1, NOT_IMPLEMENTED, EMPTY_BUFFER,
                                                       headers, EmptyHttpHeaders.INSTANCE);
            } else {
                response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.OK,
                                                       copiedBuffer(request.method().name(), US_ASCII));
            }
            ctx.writeAndFlush(response);
            ctx.close();
        });

        final ClientFactory clientFactory =
                ClientFactory.builder().proxyConfig(ProxyConfig.connect(httpProxyServer.address()))
                             .useHttp2Preface(false).build();
        final WebClient webClient = WebClient.builder(SessionProtocol.HTTP, http1Server.endpoint())
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();
        final AggregatedHttpResponse response = responseFuture.join();
        assertThat(response.status()).isEqualTo(OK);
        assertThat(response.contentUtf8()).isEqualTo(GET.name());
        assertThat(numSuccessfulProxyRequests).isEqualTo(2);
        clientFactory.close();
    }

    @Test
    void testHttpProxyPrefaceFailure() throws Exception {
        DYNAMIC_HANDLER.setChannelReadCustomizer((ctx, msg) -> {
            if (!(msg instanceof FullHttpRequest)) {
                ctx.close();
            }
            final HttpRequest request = (HttpRequest) msg;
            final DefaultFullHttpResponse response;
            if (HttpMethod.valueOf("PRI").equals(request.method())) {
                // reject http2 preface
                final HttpHeaders headers = new DefaultHttpHeaders().add(CONNECTION, "close");
                response = new DefaultFullHttpResponse(
                        HTTP_1_1, NOT_IMPLEMENTED, EMPTY_BUFFER, headers, EmptyHttpHeaders.INSTANCE);
            } else {
                response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.OK,
                                                       copiedBuffer(request.method().name(), US_ASCII));
            }
            ctx.writeAndFlush(response);
            ctx.close();
        });

        final ClientFactory clientFactory =
                ClientFactory.builder().proxyConfig(ProxyConfig.connect(httpProxyServer.address())).build();
        final WebClient webClient = WebClient.builder(SessionProtocol.HTTP, http1Server.endpoint())
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();
        final AggregatedHttpResponse response = responseFuture.join();
        assertThat(response.status()).isEqualTo(OK);
        assertThat(response.contentUtf8()).isEqualTo(GET.name());
        assertThat(numSuccessfulProxyRequests).isEqualTo(2);
        clientFactory.close();
    }

    @Test
    void testHttpsProxyBasicCase() throws Exception {
        final ClientFactory clientFactory =
                ClientFactory.builder().tlsNoVerify().proxyConfig(
                        ProxyConfig.connect(httpsProxyServer.address(), true)).build();
        final WebClient webClient = WebClient.builder(H1C, backendServer.httpEndpoint())
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();
        final AggregatedHttpResponse response = responseFuture.join();
        assertThat(response.status()).isEqualTo(OK);
        assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
        assertThat(numSuccessfulProxyRequests).isEqualTo(1);
        clientFactory.close();
    }

    @Test
    void testProxyWithH2C() throws Exception {
        final int numRequests = 5;
        final ClientFactory clientFactory = ClientFactory.builder().proxyConfig(
                ProxyConfig.socks4(socksProxyServer.address())).build();
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
        assertThat(numSuccessfulProxyRequests).isEqualTo(1);
        clientFactory.close();
    }

    @Test
    void testProxyWithUserName() throws Exception {
        final String username = "username";
        DYNAMIC_HANDLER.setChannelReadCustomizer((ctx, msg) -> {
            if (msg instanceof DefaultSocks4CommandRequest) {
                assertThat(username.equals(((DefaultSocks4CommandRequest) msg).userId()));
            }
            ctx.fireChannelRead(msg);
        });

        final ClientFactory clientFactory =
                ClientFactory.builder()
                             .proxyConfig(ProxyConfig.socks4(socksProxyServer.address(), username))
                             .build();

        final WebClient webClient = WebClient.builder(H1C, backendServer.httpEndpoint())
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();
        final AggregatedHttpResponse response = responseFuture.join();
        assertThat(response.status()).isEqualTo(OK);
        assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
        assertThat(numSuccessfulProxyRequests).isEqualTo(1);
        clientFactory.close();
    }

    @Test
    void testProxy_connectionFailure_throwsException() throws Exception {
        final int unusedPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            unusedPort = ss.getLocalPort();
        }

        final AtomicInteger failedAttempts = new AtomicInteger();
        final InetSocketAddress proxyAddress = new InetSocketAddress("127.0.0.1", unusedPort);
        final ProxyConfigSelector selector = new ProxyConfigSelector() {
            @Override
            public ProxyConfig select(URI uri) {
                assertThat(uri).hasHost(backendServer.httpSocketAddress().getHostString());
                assertThat(uri).hasPort(backendServer.httpSocketAddress().getPort());
                return ProxyConfig.socks4(proxyAddress);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, Throwable throwable) {
                assertThat(uri).hasHost(backendServer.httpSocketAddress().getHostString());
                assertThat(uri).hasPort(backendServer.httpSocketAddress().getPort());
                assertThat(sa).isEqualTo(proxyAddress);
                assertThat(throwable).isInstanceOf(ConnectException.class);
                failedAttempts.incrementAndGet();
            }
        };
        try (ClientFactory clientFactory = ClientFactory.builder().proxyConfig(selector).build()) {
            final WebClient webClient = WebClient.builder(H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();

            assertThatThrownBy(responseFuture::join).isInstanceOf(CompletionException.class)
                                                    .hasMessageContaining("Connection refused")
                                                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                                                    .hasRootCauseInstanceOf(ConnectException.class);
            await().timeout(Duration.ofSeconds(1)).untilAtomic(failedAttempts, is(1));
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
        final ProxyConfigSelector selector = new ProxyConfigSelector() {
            @Override
            public ProxyConfig select(URI uri) {
                assertThat(uri).hasHost(backendServer.httpSocketAddress().getHostString());
                assertThat(uri).hasPort(backendServer.httpSocketAddress().getPort());
                return ProxyConfig.socks4(proxyAddress);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, Throwable throwable) {
                assertThat(uri).hasHost(backendServer.httpSocketAddress().getHostString());
                assertThat(uri).hasPort(backendServer.httpSocketAddress().getPort());
                assertThat(sa).isEqualTo(proxyAddress);
                assertThat(throwable).isInstanceOf(ProxyConnectException.class);
                failedAttempts.incrementAndGet();
            }
        };
        try (ClientFactory clientFactory = ClientFactory.builder().proxyConfig(selector)
                                                        .connectTimeoutMillis(1).build()) {

            final WebClient webClient = WebClient.builder(H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            assertThatThrownBy(responseFuture::join).isInstanceOf(CompletionException.class)
                                                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                                                    .hasRootCauseInstanceOf(ProxyConnectException.class);
            await().timeout(Duration.ofSeconds(1)).untilAtomic(failedAttempts, is(1));
        }
    }

    @Test
    void testProxy_responseFailure_throwsException() throws Exception {
        DYNAMIC_HANDLER.setWriteCustomizer((ctx, msg, promise) -> {
            ctx.write(new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED), promise);
        });

        final AtomicInteger failedAttempts = new AtomicInteger();
        final InetSocketAddress proxyAddress = socksProxyServer.address();
        final ProxyConfigSelector selector = new ProxyConfigSelector() {
            @Override
            public ProxyConfig select(URI uri) {
                assertThat(uri).hasHost(backendServer.httpSocketAddress().getHostString());
                assertThat(uri).hasPort(backendServer.httpSocketAddress().getPort());
                return ProxyConfig.socks4(proxyAddress);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, Throwable throwable) {
                assertThat(uri).hasHost(backendServer.httpSocketAddress().getHostString());
                assertThat(uri).hasPort(backendServer.httpSocketAddress().getPort());
                assertThat(sa).isEqualTo(proxyAddress);
                assertThat(throwable).isInstanceOf(ConnectException.class);
                failedAttempts.incrementAndGet();
            }
        };
        try (ClientFactory clientFactory = ClientFactory.builder().proxyConfig(selector).build()) {
            final WebClient webClient = WebClient.builder(H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();

            assertThatThrownBy(responseFuture::join).isInstanceOf(CompletionException.class)
                                                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                                                    .hasRootCauseInstanceOf(ProxyConnectException.class);
            await().timeout(Duration.ofSeconds(1)).untilAtomic(failedAttempts, is(1));
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
                    new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.OK)));
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
                final ByteBuf backendMessage = ReferenceCountUtil.retain((ByteBuf) msg);
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

    private static class EnableIfDefaultSelectorCondition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            return ProxySelector.getDefault() != null ? enabled("default selector exists")
                                                      : disabled("default selector doesn't exist");
        }
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ExtendWith(EnableIfDefaultSelectorCondition.class)
    private @interface EnableIfDefaultSelector {
    }
}
