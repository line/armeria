/*
 * Copyright 2021 LINE Corporation
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
 * under the Licenses
 */

package com.linecorp.armeria.client;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.endpoint.dns.TestDnsServer;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddresses;
import io.netty.util.ReferenceCountUtil;

class HttpClientFactoryTest {
    @RegisterExtension
    public static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.streaming();
                }
            });
        }
    };

    @Test
    void numConnections() {
        final ClientFactory clientFactory = ClientFactory.builder().build();
        assertThat(clientFactory.numConnections()).isZero();

        final WebClient client = WebClient.builder(server.httpUri()).factory(clientFactory).build();
        final HttpResponse response = client.get("/");

        await().untilAsserted(() -> {
            assertThat(response.isOpen()).isTrue();
            assertThat(clientFactory.numConnections()).isOne();
        });

        clientFactory.close();
        await().untilAsserted(() -> assertThat(clientFactory.numConnections()).isZero());
    }

    @Test
    void numConnections_multipleH1Client() {
        try (ClientFactory clientFactory = ClientFactory.builder().build()) {
            for (int i = 0; i < 15; i++) {
                final WebClient client = WebClient.builder(server.httpEndpoint()
                                                                 .toUri(SessionProtocol.H1C))
                                                  .factory(clientFactory).build();
                final HttpResponse response = client.get("/");
                await().untilAsserted(() -> assertThat(response.isOpen()).isTrue());
            }
            await().untilAsserted(() -> assertThat(clientFactory.numConnections()).isEqualTo(15));
        }
    }

    @Test
    void numConnections_multipleServers() {
        try (Server server2 = Server.builder()
                                    .service("/", new AbstractHttpService() {
                                        @Override
                                        protected HttpResponse doGet(ServiceRequestContext ctx,
                                                                     HttpRequest req) {
                                            return HttpResponse.streaming();
                                        }
                                    }).build();
             ClientFactory clientFactory = ClientFactory.builder().build()) {
            server2.start().join();

            final WebClient client1 = WebClient.builder(server.httpUri())
                                               .factory(clientFactory).build();
            final HttpResponse response1 = client1.get("/");
            final WebClient client2 = WebClient.builder("http://127.0.0.1:" + server2.activeLocalPort())
                                               .factory(clientFactory).build();
            final HttpResponse response2 = client2.get("/");

            await().untilAsserted(() -> {
                assertThat(response1.isOpen()).isTrue();
                assertThat(response2.isOpen()).isTrue();
                assertThat(clientFactory.numConnections()).isEqualTo(2);
            });
        }
    }

    @Test
    void execute_dnsTimeout_clientRequestContext_isTimedOut() {
        try (TestDnsServer dnsServer = new TestDnsServer(ImmutableMap.of(), new AlwaysTimeoutHandler())) {
            try (RefreshingAddressResolverGroup group = dnsTimeoutBuilder(dnsServer)
                    .build(CommonPools.workerGroup().next())) {
                final ClientFactory clientFactory = ClientFactory
                        .builder()
                        .addressResolverGroupFactory(eventExecutors -> group)
                        .build();
                final Endpoint endpoint = Endpoint
                        .of("test")
                        .withIpAddr(null); // to invoke dns resolve address
                final WebClient client = WebClient
                        .builder(endpoint.toUri(SessionProtocol.H1C))
                        .factory(clientFactory)
                        .build();

                try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                    assertThatThrownBy(() -> client.get("/").aggregate().join())
                            .isInstanceOf(CompletionException.class)
                            .hasCauseInstanceOf(UnprocessedRequestException.class)
                            .hasRootCauseInstanceOf(DnsTimeoutException.class);
                    captor.get().whenResponseCancelled().join();
                    assertThat(captor.get().isTimedOut()).isTrue();
                }

                clientFactory.close();
                endpoint.close();
            }
        }
    }

    private static class AlwaysTimeoutHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof DatagramDnsQuery) {
                // Just release the msg and return so that the client request is timed out.
                ReferenceCountUtil.safeRelease(msg);
                return;
            }
            super.channelRead(ctx, msg);
        }
    }

    private static DnsResolverGroupBuilder dnsTimeoutBuilder(TestDnsServer... servers) {
        final DnsServerAddressStreamProvider dnsServerAddressStreamProvider =
                hostname -> DnsServerAddresses.sequential(
                        Stream.of(servers).map(TestDnsServer::addr).collect(toImmutableList())).stream();
        final DnsResolverGroupBuilder builder = new DnsResolverGroupBuilder()
                .serverAddressStreamProvider(dnsServerAddressStreamProvider)
                .meterRegistry(PrometheusMeterRegistries.newRegistry())
                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                .traceEnabled(false)
                .queryTimeoutMillis(1); // dns timeout
        return builder;
    }
}
