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
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
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
import com.linecorp.armeria.server.ProxiedAddresses;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HAProxyClientIntegrationTest {
    private static final String PROXY_PATH = "/proxy";

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

    @Test
    void testExplicitHAProxy() throws Exception {
        final InetSocketAddress srcAddr = new InetSocketAddress("127.0.0.2", 82);
        final InetSocketAddress destAddr = new InetSocketAddress("127.0.0.3", 83);

        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.haproxy(srcAddr, destAddr))
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();

            final AggregatedHttpResponse response = responseFuture.get(10, TimeUnit.SECONDS);
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            final String expectedResponse = String.format("%s-%s", srcAddr, destAddr);
            assertThat(response.contentUtf8()).isEqualTo(expectedResponse);
        }
    }

    @Test
    void testImplicitHAProxyUsesRootContextIfAvailable() throws Exception {
        final InetSocketAddress srcAddr = new InetSocketAddress("127.0.0.2", 82);
        final InetSocketAddress destAddr = new InetSocketAddress("127.0.0.3", 83);

        final ServiceRequestContext serviceRequestContext =
                ServiceRequestContext.builder(
                        HttpRequest.of(HttpMethod.GET, "/"))
                                     .proxiedAddresses(ProxiedAddresses.of(srcAddr, destAddr)).build();

        try (SafeCloseable ignored = serviceRequestContext.push();
             ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.haproxy())
                                  .useHttp2Preface(true)
                                  .build()) {

            final WebClient webClient = WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                                                 .factory(clientFactory)
                                                 .decorator(LoggingClient.newDecorator())
                                                 .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();

            final AggregatedHttpResponse response = responseFuture.get(10, TimeUnit.SECONDS);
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            final String expectedResponse = String.format("%s-%s", srcAddr, destAddr);
            assertThat(response.contentUtf8()).isEqualTo(expectedResponse);
        }
    }

    @Test
    void testImplicitHAProxyWithoutRootContextUsesDefault() throws Exception {
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .proxyConfig(ProxyConfig.haproxy())
                                  .useHttp2Preface(true)
                                  .build()) {

            final AtomicReference<InetSocketAddress> srcAddressRef = new AtomicReference<>();
            final WebClient webClient =
                    WebClient.builder(SessionProtocol.H1C, backendServer.httpEndpoint())
                             .factory(clientFactory)
                             .decorator(LoggingClient.newDecorator())
                             .decorator((delegate, ctx, req) -> {
                                 final HttpResponse response = delegate.execute(ctx, req);
                                 await().atMost(10, TimeUnit.SECONDS).until(
                                         () -> ctx.log().isAvailable(RequestLogProperty.SESSION));
                                 srcAddressRef.set(ctx.localAddress());
                                 return response;
                             })
                             .build();
            final CompletableFuture<AggregatedHttpResponse> responseFuture =
                    webClient.get(PROXY_PATH).aggregate();

            final AggregatedHttpResponse response = responseFuture.get(10, TimeUnit.SECONDS);
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            final String expectedResponse =
                    String.format("%s-%s", srcAddressRef.get(), backendServer.httpSocketAddress());
            assertThat(response.contentUtf8()).isEqualTo(expectedResponse);
        }
    }
}
