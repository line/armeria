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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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
            ch.pipeline().addLast(new IntermediaryProxyServerHandler("http", success -> {
                if (success) {
                    successCounter.incrementAndGet();
                }
            }, ImmutableMap.of("armeria.foo", backendServer.httpSocketAddress())));
        }
    };

    @BeforeEach
    void beforeEach() {
        successCounter.set(0);
    }

    @Test
    void testH1CProxyBasicCase() throws Exception {
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.connect(httpProxyServer.address()))
                                  .useHttp2Preface(true)
                                  .addressResolverGroupFactory(eventExecutors -> MockAddressResolverGroup.of(unused -> {
                                      throw new RuntimeException("unable to resolve: " + unused);
                                  }))
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
}
