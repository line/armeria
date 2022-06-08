/*
 * Copyright 2022 LINE Corporation
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.internal.testing.NettyServerExtension;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.logging.LoggingHandler;

class UnknownHostProxyTest {

    private static final AtomicInteger successCounter = new AtomicInteger();

    @RegisterExtension
    @Order(1)
    static ServerExtension backendServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.port(0, SessionProtocol.HTTP);
            sb.port(0, SessionProtocol.HTTPS);
            sb.tlsSelfSigned();
            sb.service(Route.ofCatchAll(), (ctx, req) -> HttpResponse.of(200));
        }
    };

    @RegisterExtension
    @Order(2)
    static NettyServerExtension httpProxyServer = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            ch.pipeline().addLast(new HttpServerCodec());
            ch.pipeline().addLast(new HttpObjectAggregator(1024));
            ch.pipeline().addLast(new LoggingHandler(UnknownHostProxyTest.class));
            ch.pipeline().addLast(new HttpProxyServerHandler());
            ch.pipeline().addLast(new IntermediaryProxyServerHandler(
                    "http", PROXY_CALLBACK,
                    ImmutableMap.of("armeria.foo", backendServer.httpSocketAddress())));
        }
    };

    @RegisterExtension
    @Order(2)
    static NettyServerExtension socksProxyServer = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            ch.pipeline().addLast(new SocksPortUnificationServerHandler());
            ch.pipeline().addLast(new Socks4ProxyServerHandler());
            ch.pipeline().addLast(new Socks5ProxyServerHandler());
            ch.pipeline().addLast(new IntermediaryProxyServerHandler(
                    "socks", PROXY_CALLBACK,
                    ImmutableMap.of("armeria.foo", backendServer.httpSocketAddress())));
        }
    };

    private static final Consumer<Boolean> PROXY_CALLBACK = success -> {
        if (success) {
            successCounter.incrementAndGet();
        }
    };

    @BeforeEach
    void beforeEach() {
        successCounter.set(0);
    }

    @ParameterizedTest
    @MethodSource("forwardProxyConfigs")
    void testForwardProxies(ProxyConfig proxyConfig) throws Exception {
        final MockAddressResolverGroup resolverGroup = MockAddressResolverGroup.of(unused -> {
            throw new RuntimeException("unable to resolve: " + unused);
        });
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(proxyConfig)
                                  .useHttp2Preface(true)
                                  .addressResolverGroupFactory(eventExecutors -> resolverGroup)
                                  .build()) {

            final WebClient webClient = WebClient.builder("http://armeria.foo")
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get("/").aggregate();
            final AggregatedHttpResponse response = responseFuture.join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(successCounter).hasValue(1);
        }
    }

    private static Stream<Arguments> forwardProxyConfigs() {
        return Stream.of(
                Arguments.of(ProxyConfig.connect(httpProxyServer.address())),
                Arguments.of(ProxyConfig.socks4(socksProxyServer.address())),
                Arguments.of(ProxyConfig.socks5(socksProxyServer.address()))
        );
    }
}
