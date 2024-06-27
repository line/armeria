/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.CertificateUtil;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServerTlsProviderTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final TlsProvider tlsProvider =
                    TlsProvider.builderForServer()
                               .set("*", TlsKeyPair.ofSelfSigned("default"))
                               .set("example.com", TlsKeyPair.ofSelfSigned("example.com"))
                               .build();

            sb.https(0)
              .tlsProvider(tlsProvider)
              .service("/", (ctx, req) -> {
                  final String commonName = CertificateUtil.getCommonName(ctx.sslSession());
                  return HttpResponse.of("default:" + commonName);
              })
              .virtualHost("example.com")
              .service("/", (ctx, req) -> {
                  final String commonName = CertificateUtil.getCommonName(ctx.sslSession());
                  return HttpResponse.of("virtual:" + commonName);
              });
        }
    };

    @RegisterExtension
    static final ServerExtension certRenewableServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tlsProvider(settableTlsProvider);
            sb.service("/", (ctx, req) -> {
                final String commonName = CertificateUtil.getCommonName(ctx.sslSession());
                return HttpResponse.of(commonName);
            });
        }
    };

    private static final SettableTlsProvider settableTlsProvider = new SettableTlsProvider();

    @BeforeEach
    void setUp() {
        settableTlsProvider.set(null);
    }

    @Test
    void shouldUseTlsProviderForTlsHandshake() {
        BlockingWebClient client = WebClient.builder(server.uri(SessionProtocol.HTTPS))
                                            .factory(ClientFactory.insecure())
                                            .build()
                                            .blocking();
        assertThat(client.get("/").contentUtf8()).isEqualTo("default:default");
        try (ClientFactory factory = ClientFactory.builder()
                                                   .tlsNoVerify()
                                                   .addressResolverGroupFactory(
                                                           unused -> MockAddressResolverGroup.localhost())
                                                   .build()) {
            client = WebClient.builder("https://example.com:" + server.httpsPort())
                              .factory(factory)
                              .build()
                              .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("virtual:example.com");
        }
    }

    @Test
    void shouldUseNewTlsKeyPair() {
        for (String host : ImmutableList.of("foo.com", "bar.com")) {
            settableTlsProvider.set(TlsKeyPair.ofSelfSigned(host));
            try (ClientFactory factory = ClientFactory.builder()
                                                      .tlsNoVerify()
                                                      .addressResolverGroupFactory(
                                                              unused -> MockAddressResolverGroup.localhost())
                                                      .build()) {
                final BlockingWebClient client = WebClient.builder(certRenewableServer.httpsUri())
                                                          .factory(factory)
                                                          .build()
                                                          .blocking();
                assertThat(client.get("/").contentUtf8()).isEqualTo(host);
            }
        }
    }

    @Test
    void disallowTlsProviderWhenTlsSettingsIsSet() {
        assertThatThrownBy(() -> {
            Server.builder()
                  .tls(TlsKeyPair.ofSelfSigned())
                  .tlsProvider(TlsProvider.ofServer(TlsKeyPair.ofSelfSigned()))
                  .build();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cannot configure TLS settings with a TlsProvider");

        assertThatThrownBy(() -> {
            Server.builder()
                  .tlsSelfSigned()
                  .tlsProvider(TlsProvider.ofServer(TlsKeyPair.ofSelfSigned()))
                  .build();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cannot configure TLS settings with a TlsProvider");

        assertThatThrownBy(() -> {
            Server.builder()
                  .tlsProvider(TlsProvider.ofServer(TlsKeyPair.ofSelfSigned()))
                  .virtualHost("example.com")
                  .tls(TlsKeyPair.ofSelfSigned())
                  .and()
                  .build();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cannot configure TLS settings with a TlsProvider");
    }

    private static class SettableTlsProvider implements TlsProvider {

        @Nullable
        private volatile TlsKeyPair keyPair;

        @Override
        public TlsKeyPair find(String hostname) {
            return keyPair;
        }

        public void set(@Nullable TlsKeyPair keyPair) {
            this.keyPair = keyPair;
        }
    }
}
