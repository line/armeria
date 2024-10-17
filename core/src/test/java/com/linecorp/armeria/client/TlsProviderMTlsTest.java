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

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerTlsConfig;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.ssl.ClientAuth;

class TlsProviderMTlsTest {
    @Order(0)
    @RegisterExtension
    static SelfSignedCertificateExtension sscServer = new SelfSignedCertificateExtension();
    @Order(0)
    @RegisterExtension
    static SelfSignedCertificateExtension sscClient = new SelfSignedCertificateExtension();

    @Order(1)
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final TlsProvider tlsProvider = TlsProvider.builder()
                                                       .keyPair(sscServer.tlsKeyPair())
                                                       .trustedCertificates(sscClient.certificate())
                                                       .build();
            final ServerTlsConfig tlsConfig = ServerTlsConfig.builder()
                                                             .clientAuth(ClientAuth.REQUIRE)
                                                             .build();
            sb.tlsProvider(tlsProvider, tlsConfig);

            sb.service("/", (ctx, req) -> {
                return HttpResponse.of(HttpStatus.OK);
            });
        }
    };

    @Test
    void testMTls() {
        final TlsProvider tlsProvider = TlsProvider
                .builder()
                .keyPair(sscClient.tlsKeyPair())
                .trustedCertificates(sscServer.certificate())
                .build();
        try (ClientFactory factory = ClientFactory
                .builder()
                .tlsProvider(tlsProvider)
                .connectTimeoutMillis(Long.MAX_VALUE)
                .build()) {
            final BlockingWebClient client = WebClient.builder(server.httpsUri())
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            final AggregatedHttpResponse res = client.get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
        }
    }
}
