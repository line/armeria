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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.testing.NettyServerExtension;
import com.linecorp.armeria.internal.testing.SimpleChannelHandlerFactory;
import com.linecorp.armeria.server.ProxiedAddresses;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.ReferenceCountUtil;

class HAProxyClientIntegrationTest {
    private static final String PROXY_PATH = "/proxy";

    private static final SimpleChannelHandlerFactory NOOP_CHANNEL_HANDLER_FACTORY =
            new SimpleChannelHandlerFactory(null, null);

    private static SimpleChannelHandlerFactory channelHandlerFactory = NOOP_CHANNEL_HANDLER_FACTORY;

    @RegisterExtension
    static ServerExtension backendServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.port(0, SessionProtocol.HTTP, SessionProtocol.PROXY);
            sb.port(0, SessionProtocol.HTTPS, SessionProtocol.PROXY);
            sb.tlsSelfSigned();
            sb.service(PROXY_PATH, (ctx, req) -> {
                assertThat(ctx.proxiedAddresses().destinationAddresses()).hasSize(1);
                final String proxyString = String.format("%s-%s", ctx.proxiedAddresses().sourceAddress(),
                                                         ctx.proxiedAddresses().destinationAddresses().get(0));
                return HttpResponse.of(proxyString);
            });
        }
    };

    @RegisterExtension
    static NettyServerExtension http1Server = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            ch.pipeline().addLast(new LoggingHandler(getClass()));
            ch.pipeline().addLast(new HAProxyMessageDecoder());
            ch.pipeline().addLast(new HttpServerCodec());
            ch.pipeline().addLast(new HttpObjectAggregator(1024));
            ch.pipeline().addLast(channelHandlerFactory.newHandler());
        }
    };

    @ParameterizedTest
    @ArgumentsSource(ProtocolEndpointProvider.class)
    void testExplicitHAProxy(SessionProtocol sessionProtocol, InetSocketAddress proxyAddr) throws Exception {
        final InetSocketAddress srcAddr = new InetSocketAddress("127.0.0.2", 82);
        final InetSocketAddress destAddr = new InetSocketAddress("127.0.0.3", 83);
        final Endpoint destEndpoint = Endpoint.of(destAddr.getHostString(), destAddr.getPort());

        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.haproxy(proxyAddr, srcAddr))
                                  .tlsNoVerify()
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(sessionProtocol, destEndpoint)
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();

            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            final String expectedResponse = String.format("%s-%s", srcAddr,
                                                          destAddr);
            assertThat(response.contentUtf8()).isEqualTo(expectedResponse);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ProtocolEndpointProvider.class)
    void testSourceAddrFromRootContextIfAvailable(
            SessionProtocol sessionProtocol, InetSocketAddress proxyAddr) throws Exception {
        final InetSocketAddress srcAddr = new InetSocketAddress("127.0.0.2", 82);
        final InetSocketAddress destAddr = new InetSocketAddress("127.0.0.3", 83);
        final Endpoint destEndpoint = Endpoint.of(destAddr.getHostString(), destAddr.getPort());

        final ServiceRequestContext serviceRequestContext =
                ServiceRequestContext.builder(
                        HttpRequest.of(HttpMethod.GET, "/"))
                                     .proxiedAddresses(ProxiedAddresses.of(srcAddr)).build();

        try (SafeCloseable ignored = serviceRequestContext.push();
             ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.haproxy(proxyAddr))
                                  .tlsNoVerify()
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(sessionProtocol, destEndpoint)
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();

            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(destEndpoint.ipAddr()).isNotNull();

            final String expectedResponse = String.format("%s-%s", srcAddr, destAddr);
            assertThat(response.contentUtf8()).isEqualTo(expectedResponse);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ProtocolEndpointProvider.class)
    void testImplicitHAProxyWithoutRootContextUsesDefault(
            SessionProtocol sessionProtocol, InetSocketAddress proxyAddr) throws Exception {
        final InetSocketAddress destAddr = new InetSocketAddress("127.0.0.3", 83);
        final Endpoint destEndpoint = Endpoint.of(destAddr.getHostString(), destAddr.getPort());
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.haproxy(proxyAddr))
                                  .tlsNoVerify()
                                  .useHttp2Preface(true)
                                  .build()) {

            final AtomicReference<InetSocketAddress> srcAddressRef = new AtomicReference<>();
            final WebClient webClient =
                    WebClient.builder(sessionProtocol, destEndpoint)
                             .factory(clientFactory)
                             .decorator(LoggingClient.newDecorator())
                             .decorator((delegate, ctx, req) -> {
                                 final HttpResponse response = delegate.execute(ctx, req);
                                 ctx.log()
                                    .whenAvailable(RequestLogProperty.SESSION)
                                    .thenAccept(log ->  srcAddressRef.set(log.context().localAddress()));
                                 return response;
                             })
                             .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();

            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            final String expectedResponse = String.format("%s-%s", srcAddressRef.get(), destAddr);
            assertThat(response.contentUtf8()).isEqualTo(expectedResponse);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ProtocolEndpointProvider.class)
    void testDifferentIpFamily(SessionProtocol sessionProtocol, InetSocketAddress proxyAddr) throws Exception {
        final InetSocketAddress srcAddr = new InetSocketAddress("127.0.0.2", 82);
        final InetSocketAddress destAddr = new InetSocketAddress("0:0:0:0:0:0:0:1", 83);
        final Endpoint destEndpoint = Endpoint.of(destAddr.getHostString(), destAddr.getPort());
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.haproxy(proxyAddr, srcAddr))
                                  .tlsNoVerify()
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(sessionProtocol, destEndpoint)
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();

            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            final String expectedResponse = String.format("%s-%s", srcAddr, destAddr);
            assertThat(response.contentUtf8()).isEqualTo(expectedResponse);
        }
    }

    @Test
    void testConnectionFailure() throws Exception {
        final int unusedPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            unusedPort = ss.getLocalPort();
        }
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.haproxy(new InetSocketAddress(unusedPort)))
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();

            assertThatThrownBy(responseFuture::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                    .hasRootCauseInstanceOf(ConnectException.class);
        }
    }

    @Test
    void testHttpProxyUpgradeRequestFailure() throws Exception {
        final InetSocketAddress srcAddr = new InetSocketAddress("127.0.0.2", 82);
        final InetSocketAddress destAddr = new InetSocketAddress("127.0.0.2", 82);
        final Endpoint destEndpoint = Endpoint.of(destAddr.getHostString(), destAddr.getPort());
        final Endpoint proxyEndpoint = http1Server.endpoint();
        assert proxyEndpoint.ipAddr() != null;
        final InetSocketAddress proxyAddr = new InetSocketAddress(proxyEndpoint.ipAddr(), proxyEndpoint.port());

        final AtomicReference<HAProxyMessage> msgRef = new AtomicReference<>();
        channelHandlerFactory = SimpleChannelHandlerFactory.onChannelRead((ctx, msg) -> {
            if (msg instanceof HAProxyMessage) {
                msgRef.set((HAProxyMessage) msg);
                return;
            }
            final HAProxyMessage proxyMsg = msgRef.get();
            final FullHttpRequest request = (FullHttpRequest) msg;
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
                                                       Unpooled.copiedBuffer(toString(proxyMsg),
                                                                             StandardCharsets.US_ASCII));
            }

            ReferenceCountUtil.release(proxyMsg);
            ReferenceCountUtil.release(msg);
            ctx.writeAndFlush(response);
            ctx.close();
        });

        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.haproxy(proxyAddr, srcAddr))
                                  .useHttp2Preface(false)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.HTTP, destEndpoint)
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            final String expectedResponse = String.format("%s-%s", srcAddr, destAddr);
            assertThat(response.contentUtf8()).isEqualTo(expectedResponse);
        }
    }

    @Test
    void testHttpProxyPrefaceFailure() throws Exception {
        final InetSocketAddress srcAddr = new InetSocketAddress("127.0.0.2", 82);
        final InetSocketAddress destAddr = new InetSocketAddress("127.0.0.2", 82);
        final Endpoint destEndpoint = Endpoint.of(destAddr.getHostString(), destAddr.getPort());
        final Endpoint proxyEndpoint = http1Server.endpoint();
        assert proxyEndpoint.ipAddr() != null;
        final InetSocketAddress proxyAddr = new InetSocketAddress(proxyEndpoint.ipAddr(), proxyEndpoint.port());

        final AtomicReference<HAProxyMessage> msgRef = new AtomicReference<>();
        channelHandlerFactory = SimpleChannelHandlerFactory.onChannelRead((ctx, msg) -> {
            if (msg instanceof HAProxyMessage) {
                msgRef.set((HAProxyMessage) msg);
                return;
            }
            final HAProxyMessage proxyMsg = msgRef.get();
            final FullHttpRequest request = (FullHttpRequest) msg;
            final DefaultFullHttpResponse response;
            if ("PRI".equals(request.method().name())) {
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
                                                       Unpooled.copiedBuffer(toString(proxyMsg),
                                                                             StandardCharsets.US_ASCII));
            }

            ReferenceCountUtil.release(proxyMsg);
            ReferenceCountUtil.release(msg);
            ctx.writeAndFlush(response);
            ctx.close();
        });

        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.haproxy(proxyAddr, srcAddr))
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.HTTP, destEndpoint)
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            final String expectedResponse = String.format("%s-%s", srcAddr, destAddr);
            assertThat(response.contentUtf8()).isEqualTo(expectedResponse);
        }
    }

    private static class ProtocolEndpointProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.concat(
                    SessionProtocol.httpValues().stream()
                                   .map(p -> arguments(p, backendServer.httpSocketAddress())),
                    SessionProtocol.httpsValues().stream()
                                   .map(p -> arguments(p, backendServer.httpsSocketAddress()))
            );
        }
    }

    private static String toString(HAProxyMessage message) {
        return String.format("%s-%s", new InetSocketAddress(message.sourceAddress(), message.sourcePort()),
                             new InetSocketAddress(message.destinationAddress(), message.destinationPort()));
    }
}
