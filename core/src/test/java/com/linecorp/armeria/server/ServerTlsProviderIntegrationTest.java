/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.util.CertificateUtil;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServerTlsProviderIntegrationTest {

    private static final TlsKeyPair defaultKeyPair = TlsKeyPair.ofSelfSigned("default");
    private static final TlsKeyPair apiKeyPair = TlsKeyPair.ofSelfSigned("api-vhost");
    private static final TlsKeyPair providerKeyPair = TlsKeyPair.ofSelfSigned("from-provider");

    private static final AtomicReference<ServerTlsProvider> providerRef = new AtomicReference<>();

    @RegisterExtension
    private static final EventLoopExtension eventLoop = new EventLoopExtension();

    // Single server with a delegating provider + static TLS fallback + vhost
    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final ServerTlsProvider delegating = ctx -> providerRef.get().serverTlsSpec(ctx);
            sb.https(0)
              .tlsProvider(delegating)
              .tls(defaultKeyPair)
              .service("/", (ctx, req) -> {
                  final String cn = CertificateUtil.getHostname(ctx.sslSession());
                  return HttpResponse.of("default:" + cn);
              })
              .virtualHost("api.example.com")
              .tls(apiKeyPair)
              .service("/", (ctx, req) -> {
                  final String cn = CertificateUtil.getHostname(ctx.sslSession());
                  return HttpResponse.of("api:" + cn);
              });
        }
    };

    @BeforeEach
    void setUp() {
        // Reset to a working provider so server stays healthy between tests
        providerRef.set(ServerTlsProvider.of(ctx ->
                ServerTlsSpec.builder().tlsKeyPair(defaultKeyPair).build()));
    }

    @Test
    void syncProvider() {
        providerRef.set(ServerTlsProvider.of(ctx -> {
            return ServerTlsSpec.builder().tlsKeyPair(providerKeyPair).build();
        }));
        try (ClientFactory factory = ClientFactory.builder().tlsNoVerify().build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(SessionProtocol.HTTPS))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("default:from-provider");
        }
    }

    @Test
    void asyncProvider() {
        providerRef.set(ctx -> {
            final CompletableFuture<ServerTlsSpec> future = new CompletableFuture<>();
            eventLoop.get().schedule(() -> {
                future.complete(ServerTlsSpec.builder()
                                             .tlsKeyPair(providerKeyPair)
                                             .build());
            }, 500, TimeUnit.MILLISECONDS);
            return future;
        });
        try (ClientFactory factory = ClientFactory.builder().tlsNoVerify().build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(SessionProtocol.HTTPS))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("default:from-provider");
        }
    }

    @Test
    void providerReturnsNull_fallsBackToStaticTls() {
        providerRef.set(ctx -> UnmodifiableFuture.completedFuture(null));
        try (ClientFactory factory = ClientFactory.builder().tlsNoVerify().build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(SessionProtocol.HTTPS))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("default:default");
        }
    }

    @Test
    void providerTakesPriorityOverStaticTls() {
        providerRef.set(ServerTlsProvider.of(ctx ->
                ServerTlsSpec.builder().tlsKeyPair(providerKeyPair).build()));
        try (ClientFactory factory = ClientFactory.builder().tlsNoVerify().build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(SessionProtocol.HTTPS))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            // Provider cert ("from-provider") should be used, not static ("default")
            assertThat(client.get("/").contentUtf8()).isEqualTo("default:from-provider");
        }
    }

    @Test
    void selectiveFallback_providerHandlesHostname() {
        providerRef.set(ctx -> {
            if ("api.example.com".equals(ctx.sniHostname())) {
                return UnmodifiableFuture.completedFuture(
                        ServerTlsSpec.builder().tlsKeyPair(providerKeyPair).build());
            }
            return UnmodifiableFuture.completedFuture(null);
        });
        try (ClientFactory factory = ClientFactory.builder()
                                                  .tlsNoVerify()
                                                  .addressResolverGroupFactory(
                                                          unused -> MockAddressResolverGroup.localhost())
                                                  .build()) {
            assertThat(WebClient.builder("https://api.example.com:" + server.httpsPort())
                                .factory(factory)
                                .build()
                                .blocking()
                                .get("/")
                                .contentUtf8()).isEqualTo("api:from-provider");
        }
    }

    @Test
    void selectiveFallback_providerReturnsNull_usesVhostTls() {
        // Provider returns null for all hostnames → falls back to per-vhost static TLS
        providerRef.set(ctx -> UnmodifiableFuture.completedFuture(null));
        try (ClientFactory factory = ClientFactory.builder()
                                                  .tlsNoVerify()
                                                  .addressResolverGroupFactory(
                                                          unused -> MockAddressResolverGroup.localhost())
                                                  .build()) {
            // api.example.com → provider returns null → falls back to api vhost's own TLS cert
            assertThat(WebClient.builder("https://api.example.com:" + server.httpsPort())
                                .factory(factory)
                                .build()
                                .blocking()
                                .get("/")
                                .contentUtf8()).isEqualTo("api:api-vhost");

            // unknown hostname → provider returns null → falls back to default vhost TLS
            assertThat(WebClient.builder("https://unknown.example.com:" + server.httpsPort())
                                .factory(factory)
                                .build()
                                .blocking()
                                .get("/")
                                .contentUtf8()).isEqualTo("default:default");
        }
    }

    @Test
    void providerThrows_connectionFails() {
        providerRef.set(ServerTlsProvider.of(ctx -> {
            throw new RuntimeException("provider failed");
        }));
        try (ClientFactory factory = ClientFactory.builder().tlsNoVerify().build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(SessionProtocol.HTTPS))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            assertThatThrownBy(() -> client.get("/"))
                    .isInstanceOf(UnprocessedRequestException.class)
                    .hasCauseInstanceOf(ClosedChannelException.class);
        }
    }

    @Test
    void providerCompletesExceptionally_connectionFails() {
        providerRef.set(ctx -> {
            return UnmodifiableFuture.exceptionallyCompletedFuture(new RuntimeException("async failure"));
        });
        try (ClientFactory factory = ClientFactory.builder().tlsNoVerify().build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(SessionProtocol.HTTPS))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            assertThatThrownBy(() -> client.get("/"))
                    .isInstanceOf(UnprocessedRequestException.class)
                    .hasCauseInstanceOf(ClosedChannelException.class);
        }
    }

    @Test
    void providerReturnsNullFuture_connectionFails() {
        providerRef.set(ctx -> null);
        try (ClientFactory factory = ClientFactory.builder().tlsNoVerify().build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(SessionProtocol.HTTPS))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            assertThatThrownBy(() -> client.get("/"))
                    .isInstanceOf(UnprocessedRequestException.class)
                    .hasCauseInstanceOf(ClosedChannelException.class);
        }
    }
}
