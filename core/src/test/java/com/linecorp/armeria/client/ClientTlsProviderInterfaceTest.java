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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerTlsConfig;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.ssl.ClientAuth;

class ClientTlsProviderInterfaceTest {

    @Order(0)
    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension();

    @Order(1)
    @RegisterExtension
    static final SelfSignedCertificateExtension sniCert = new SelfSignedCertificateExtension("sni.host");

    @Order(2)
    @RegisterExtension
    static final SelfSignedCertificateExtension clientCert = new SelfSignedCertificateExtension();

    @Order(3)
    @RegisterExtension
    static final SelfSignedCertificateExtension untrustedCert = new SelfSignedCertificateExtension();

    @Order(4)
    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.https(0)
              .tls(serverCert.tlsKeyPair())
              .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @Order(5)
    @RegisterExtension
    static final ServerExtension sniServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.https(0)
              .tlsProvider(TlsProvider.builder()
                                      .keyPair(serverCert.tlsKeyPair())
                                      .keyPair("sni.host", sniCert.tlsKeyPair())
                                      .build())
              .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @Order(6)
    @RegisterExtension
    static final ServerExtension mtlsServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.https(0)
              .tlsProvider(TlsProvider.builder()
                                      .keyPair(serverCert.tlsKeyPair())
                                      .trustedCertificates(clientCert.certificate())
                                      .build(),
                           ServerTlsConfig.builder().clientAuth(ClientAuth.REQUIRE).build())
              .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @Test
    void success() {
        final ClientTlsProvider provider = ctx ->
                ClientTlsSpec.builder()
                             .trustedCertificates(serverCert.certificate())
                             .build();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .tlsProvider(provider).build()) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpsUri())
                             .factory(factory).build().blocking();
            assertThat(client.get("/").status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void failure_untrustedCert() {
        final ClientTlsProvider provider = ctx ->
                ClientTlsSpec.builder()
                             .trustedCertificates(untrustedCert.certificate())
                             .build();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .tlsProvider(provider).build()) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpsUri())
                             .factory(factory).build().blocking();
            assertThatThrownBy(() -> client.get("/"))
                    .isInstanceOf(UnprocessedRequestException.class)
                    .hasCauseInstanceOf(javax.net.ssl.SSLHandshakeException.class);
        }
    }

    @Test
    void mtls() {
        final ClientTlsProvider provider = ctx ->
                ClientTlsSpec.builder()
                             .trustedCertificates(serverCert.certificate())
                             .tlsKeyPair(clientCert.tlsKeyPair())
                             .build();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .tlsProvider(provider).build()) {
            final BlockingWebClient client =
                    WebClient.builder(mtlsServer.httpsUri())
                             .factory(factory).build().blocking();
            assertThat(client.get("/").status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void throwsException() {
        final ClientTlsProvider provider = ctx -> {
            throw new RuntimeException("provider error");
        };
        try (ClientFactory factory = ClientFactory.builder()
                                                  .tlsProvider(provider).build()) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpsUri())
                             .factory(factory).build().blocking();
            assertThatThrownBy(() -> client.get("/"))
                    .isInstanceOf(UnprocessedRequestException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("provider error");
        }
    }

    @Test
    void returnsNull() {
        final ClientTlsProvider provider = ctx -> null;
        try (ClientFactory factory = ClientFactory.builder()
                                                  .tlsProvider(provider).build()) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpsUri())
                             .factory(factory).build().blocking();
            assertThatThrownBy(() -> client.get("/"))
                    .isInstanceOf(UnprocessedRequestException.class)
                    .hasCauseInstanceOf(NullPointerException.class)
                    .hasMessageContaining(
                            "ClientTlsProvider.clientTlsSpec() returned null");
        }
    }

    @Test
    void setSniHostname() {
        final ClientTlsProvider provider = ctx -> {
            assertThat(ctx.sniHostname()).isEqualTo("sni.host");
            return ClientTlsSpec.builder()
                                .trustedCertificates(sniCert.certificate())
                                .build();
        };
        try (ClientFactory factory = ClientFactory.builder()
                                                  .tlsProvider(provider).build()) {
            final BlockingWebClient client =
                    WebClient.builder(sniServer.httpsUri())
                             .factory(factory)
                             .decorator((delegate, ctx, req) -> {
                                 ctx.setSniHostname("sni.host");
                                 return delegate.execute(ctx, req);
                             })
                             .build().blocking();
            assertThat(client.get("/").status())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void sniHostnameAvailable() {
        final ClientTlsProvider provider = ctx -> {
            assertThat(ctx.sniHostname()).isEqualTo("example.com");
            return ClientTlsSpec.builder()
                                .trustedCertificates(serverCert.certificate())
                                .endpointIdentificationAlgorithm("")
                                .build();
        };
        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .addressResolverGroupFactory(unused -> MockAddressResolverGroup.localhost())
                                  .tlsProvider(provider).build()) {
            final BlockingWebClient client =
                    WebClient.builder("https://example.com:" + server.httpsPort())
                             .factory(factory).build().blocking();
            assertThat(client.get("/").status()).isEqualTo(HttpStatus.OK);
        }
    }
}
