/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.common.SslContextFactory;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.Channel;
import io.netty.handler.ssl.ClientAuth;

class TlsProviderCacheTest {

    @Order(0)
    @RegisterExtension
    static final SelfSignedCertificateExtension clientFooCert = new SelfSignedCertificateExtension();

    @Order(0)
    @RegisterExtension
    static final SelfSignedCertificateExtension clientBarCert = new SelfSignedCertificateExtension();

    @Order(0)
    @RegisterExtension
    static final SelfSignedCertificateExtension serverFooCert = new SelfSignedCertificateExtension("foo.com");

    @Order(0)
    @RegisterExtension
    static final SelfSignedCertificateExtension serverBarCert = new SelfSignedCertificateExtension("bar.com");

    static CompletableFuture<Void> startFuture;

    @Order(1)
    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tls(serverFooCert.tlsKeyPair());
            sb.tlsCustomizer(b -> {
                b.clientAuth(ClientAuth.REQUIRE)
                 .trustManager(clientFooCert.certificate());
            });

            sb.virtualHost("bar.com")
              .tls(serverBarCert.tlsKeyPair())
              .tlsCustomizer(b -> {
                  b.clientAuth(ClientAuth.REQUIRE)
                   .trustManager(clientBarCert.certificate());
              });

            sb.service("/", (ctx, req) -> {
                final CompletableFuture<HttpResponse> future =
                        startFuture.thenApply(unused -> HttpResponse.of("Hello!"));
                return HttpResponse.of(future);
            });
        }
    };

    @BeforeEach
    void setUp() {
        startFuture = new CompletableFuture<>();
    }

    @Test
    void test() {
        final ClientFactory factory =
                ClientFactory.builder()
                             .tlsCustomizer(b -> {
                                 b.trustManager(serverFooCert.certificate(),
                                                serverBarCert.certificate());
                             })
                             .build();
        final BlockingWebClient client =
                WebClient.builder("https://google.com")
                         .factory(factory)
                         .build()
                         .blocking();
        final AggregatedHttpResponse res = client.get("/");
        System.out.println(res);
    }

    @Test
    void shouldCacheSslContext() {
        // This test could be broken if multiple tests are running in parallel.
        final CountingConnectionPoolListener poolListener = new CountingConnectionPoolListener();
        final TlsProvider tlsProvider =
                TlsProvider.builder()
                           .set("foo.com", clientFooCert.tlsKeyPair())
                           .set("bar.com", clientBarCert.tlsKeyPair())
                           .trustedCertificates(serverFooCert.certificate())
                           .trustedCertificates(serverBarCert.certificate())
                           .build();

        final List<Channel> channels = new ArrayList<>();
        final List<CompletableFuture<AggregatedHttpResponse>> responses = new ArrayList<>();
        try (
                ClientFactory factory = ClientFactory
                        .builder()
                        .addressResolverGroupFactory(eventLoopGroup -> MockAddressResolverGroup.localhost())
                        .tlsProvider(tlsProvider)
                        .connectionPoolListener(poolListener)
                        .build()) {
            for (String host : ImmutableList.of("foo.com", "bar.com")) {
                final WebClient client =
                        // Use HTTP/1 to create multiple connections.
                        WebClient.builder("h1://" + host + ':' + server.httpsPort())
                                 .factory(factory)
                                 .build();

                for (int i = 0; i < 3; i++) {
                    try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                        final CompletableFuture<AggregatedHttpResponse> future =
                                client.prepare()
                                      .get("/")
                                      .header(HttpHeaderNames.CONNECTION, "close")
                                      .execute()
                                      .aggregate();
                        responses.add(future);
                        channels.add(captor.get().log()
                                           .whenAvailable(RequestLogProperty.REQUEST_HEADERS).join()
                                           .channel());
                    }
                }
            }

            startFuture.complete(null);
            for (AggregatedHttpResponse response : CompletableFutures.allAsList(responses).join()) {
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                assertThat(response.contentUtf8()).isEqualTo("Hello!");
            }

            await().untilAsserted(() -> {
                assertThat(poolListener.opened()).isEqualTo(6);
            });
            assertThat(channels).hasSize(6);

            final HttpClientFactory clientFactory = (HttpClientFactory) factory.unwrap();
            final SslContextFactory sslContextFactory = clientFactory.sslContextFactory();
            assertThat(sslContextFactory).isNotNull();
            // Make sure the SslContext is reused after the connection is closed.
            assertThat(sslContextFactory.numCachedContexts()).isEqualTo(2);
        }
    }
}
