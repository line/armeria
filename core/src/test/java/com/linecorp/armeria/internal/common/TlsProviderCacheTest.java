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

package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.CountingConnectionPoolListener;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

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
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Disabled("Manually run this test to check the shared cache behavior")
    @Test
    void shouldCacheSslContext() {
        // This test could be broken if multiple tests are running in parallel.
        TlsProviderUtil.sslContextCache.invalidateAll();
        final CountingConnectionPoolListener poolListener = new CountingConnectionPoolListener();
        final TlsProvider tlsProvider = TlsProvider.builderForClient()
                                                   .set("foo.com", clientFooCert.tlsKeyPair())
                                                   .set("bar.com", clientBarCert.tlsKeyPair())
                                                   .tlsCustomizer(b -> {
                                                       b.trustManager(serverFooCert.certificate(),
                                                                      serverBarCert.certificate());
                                                   })
                                                   .build();
        try (ClientFactory factory = ClientFactory
                .builder()
                .addressResolverGroupFactory(eventLoopGroup -> MockAddressResolverGroup.localhost())
                .tlsProvider(tlsProvider)
                .connectionPoolListener(poolListener)
                .build()) {
            for (String host : ImmutableList.of("foo.com", "bar.com")) {
                final BlockingWebClient client =
                        WebClient.builder("https://" + host + ':' + server.httpsPort())
                                 .factory(factory)
                                 .build()
                                 .blocking();

                for (int i = 0; i < 3; i++) {
                    final AggregatedHttpResponse res =
                            client.prepare()
                                  .get("/")
                                  .header(HttpHeaderNames.CONNECTION, "close")
                                  .execute();
                    assertThat(res.status().code()).isEqualTo(200);
                }
            }
            assertThat(poolListener.opened()).isEqualTo(6);
        }
        // Make sure the SslContext is reused after the connection is closed.
        assertThat(TlsProviderUtil.sslContextCache.asMap()).hasSize(2);
    }
}
