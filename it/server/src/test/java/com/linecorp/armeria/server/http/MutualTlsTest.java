/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.server.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientTlsConfig;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerTlsConfig;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.junit5.server.SignedCertificateExtension;

import io.netty.handler.ssl.ClientAuth;

class MutualTlsTest {

    @Order(1)
    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert1 = new SelfSignedCertificateExtension();

    @Order(2)
    @RegisterExtension
    static final SignedCertificateExtension serverCert2 = new SignedCertificateExtension(serverCert1);

    @Order(3)
    @RegisterExtension
    static final SignedCertificateExtension serverCert3 = new SignedCertificateExtension(serverCert2);

    @Order(4)
    @RegisterExtension
    static final SelfSignedCertificateExtension clientCert1 = new SelfSignedCertificateExtension();

    @Order(5)
    @RegisterExtension
    static final SignedCertificateExtension clientCert2 = new SignedCertificateExtension(clientCert1);

    @Order(6)
    @RegisterExtension
    static final SignedCertificateExtension clientCert3 = new SignedCertificateExtension(clientCert2);

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final TlsKeyPair tlsKeyPair = TlsKeyPair.of(serverCert3.privateKey(),
                                                        serverCert3.certificate(),
                                                        serverCert2.certificate());
            sb.tlsProvider(TlsProvider.of(tlsKeyPair),
                           ServerTlsConfig.builder()
                                          .clientAuth(ClientAuth.REQUIRE)
                                          .tlsCustomizer(b -> b.trustManager(clientCert1.certificate()))
                                          .build());

            sb.service("/", (ctx, req) -> HttpResponse.of("success"));
            sb.decorator(LoggingService.builder().newDecorator());
        }
    };

    @Test
    void normal() {
        final TlsKeyPair tlsKeyPair = TlsKeyPair.of(clientCert3.privateKey(),
                                                    clientCert3.certificate(),
                                                    clientCert2.certificate());
        final ClientTlsConfig tlsConfig =
                ClientTlsConfig.builder()
                               .tlsCustomizer(b -> b.trustManager(serverCert1.certificate()))
                               .build();
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .tlsProvider(TlsProvider.of(tlsKeyPair), tlsConfig)
                                  .build()) {
            final BlockingWebClient client = WebClient.builder(server.httpsUri())
                                                      .factory(clientFactory)
                                                      .decorator(LoggingClient.newDecorator())
                                                      .build()
                                                      .blocking();
            assertThat(client.get("/").status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void shouldAllowOverridingTlsKey() {
        assertThatCode(() -> {
            ClientFactory.builder()
                         .tls(clientCert1.certificateFile(), clientCert1.privateKeyFile())
                         .tls(clientCert1.privateKey(), clientCert1.certificate());
        }).doesNotThrowAnyException();
    }
}
