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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.internal.common.util.CertificateUtil;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServerTlsProviderTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final TlsProvider tlsProvider =
                    TlsProvider.builder()
                               .set("*", TlsKeyPair.ofSelfSinged("default"))
                               .set("example.com", TlsKeyPair.ofSelfSinged("example.com"))
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

    @Test
    void shouldUseTlsProviderForTlsHandshake() {
        BlockingWebClient client = WebClient.builder(server.uri(SessionProtocol.HTTPS))
                                            .factory(ClientFactory.insecure())
                                            .build()
                                            .blocking();
        assertThat(client.get("/").contentUtf8()).isEqualTo("default:default");
        client = WebClient.builder("https://example.com:" + server.httpsPort())
                          .factory(ClientFactory.builder()
                                                .tlsNoVerify()
                                                .addressResolverGroupFactory(
                                                        unused -> MockAddressResolverGroup.localhost())
                                                .build())
                          .build()
                          .blocking();
        assertThat(client.get("/").contentUtf8()).isEqualTo("virtual:example.com");
    }
}
