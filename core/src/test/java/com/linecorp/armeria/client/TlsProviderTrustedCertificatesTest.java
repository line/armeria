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

import java.security.cert.X509Certificate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerTlsConfig;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.ssl.ClientAuth;

class TlsProviderTrustedCertificatesTest {

    @Order(0)
    @RegisterExtension
    static SelfSignedCertificateExtension serverCertFoo = new SelfSignedCertificateExtension("foo.com");

    @Order(0)
    @RegisterExtension
    static SelfSignedCertificateExtension serverCertBar = new SelfSignedCertificateExtension("bar.com");

    @Order(0)

    @RegisterExtension
    static SelfSignedCertificateExtension serverCertDefault = new SelfSignedCertificateExtension();

    @Order(0)
    @RegisterExtension
    static SelfSignedCertificateExtension clientCertFoo = new SelfSignedCertificateExtension();

    @Order(0)
    @RegisterExtension
    static SelfSignedCertificateExtension clientCertBar = new SelfSignedCertificateExtension();

    @Order(0)
    @RegisterExtension
    static SelfSignedCertificateExtension clientCertDefault = new SelfSignedCertificateExtension();

    @Order(1)
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final TlsProvider tlsProvider =
                    TlsProvider.builder()
                               .keyPair("foo.com", serverCertFoo.tlsKeyPair())
                               .keyPair("bar.com", serverCertBar.tlsKeyPair())
                               .keyPair(serverCertDefault.tlsKeyPair())
                               .trustedCertificates("foo.com", clientCertFoo.certificate())
                               .trustedCertificates("bar.com", clientCertBar.certificate())
                               .trustedCertificates(clientCertDefault.certificate())
                               .build();
            final ServerTlsConfig tlsConfig = ServerTlsConfig.builder()
                                                             .clientAuth(ClientAuth.REQUIRE)
                                                             .build();
            sb.https(0);
            sb.tlsProvider(tlsProvider, tlsConfig);
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @RegisterExtension
    static ServerExtension fooServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final TlsProvider tlsProvider = TlsProvider.builder()
                                                       .keyPair("foo.com", serverCertFoo.tlsKeyPair())
                                                       .trustedCertificates("foo.com",
                                                                            clientCertFoo.certificate())
                                                       .build();
            final ServerTlsConfig tlsConfig = ServerTlsConfig.builder()
                                                             .clientAuth(ClientAuth.REQUIRE)
                                                             .build();
            sb.https(0);
            sb.tlsProvider(tlsProvider, tlsConfig);
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @RegisterExtension
    static ServerExtension barServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final TlsProvider tlsProvider =
                    TlsProvider.builder()
                               .keyPair("bar.com", serverCertBar.tlsKeyPair())
                               .trustedCertificates("bar.com", clientCertBar.certificate())
                               .build();
            final ServerTlsConfig tlsConfig = ServerTlsConfig.builder()
                                                             .clientAuth(ClientAuth.REQUIRE)
                                                             .build();
            sb.https(0);
            sb.tlsProvider(tlsProvider, tlsConfig);
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @Test
    void complexUsage() {
        final TlsProvider tlsProvider =
                TlsProvider.builder()
                           .keyPair("foo.com", clientCertFoo.tlsKeyPair())
                           .keyPair("bar.com", clientCertBar.tlsKeyPair())
                           .keyPair(clientCertDefault.tlsKeyPair())
                           .trustedCertificates(serverCertDefault.certificate())
                           .trustedCertificates("foo.com", serverCertFoo.certificate())
                           .trustedCertificates("bar.com", serverCertBar.certificate())
                           .build();
        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .addressResolverGroupFactory(
                                          unused -> MockAddressResolverGroup.localhost())
                                  .tlsProvider(tlsProvider)
                                  .build()) {
            for (String hostname : ImmutableList.of("foo.com", "bar.com", "127.0.0.1")) {
                final BlockingWebClient client =
                        WebClient.builder("https://" + hostname + ':' + server.httpsPort())
                                 .factory(factory)
                                 .build()
                                 .blocking();
                assertThat(client.get("/").status()).isEqualTo(HttpStatus.OK);
            }
        }
    }

    @MethodSource("simpleParameters")
    @ParameterizedTest
    void simpleUsage(String hostname, int port, TlsKeyPair keyPair, X509Certificate trustedCertificate) {
        final TlsProvider tlsProvider =
                TlsProvider.builder()
                           .keyPair(hostname, keyPair)
                           .trustedCertificates(hostname, trustedCertificate)
                           .build();
        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .addressResolverGroupFactory(
                                          unused -> MockAddressResolverGroup.localhost())
                                  .tlsProvider(tlsProvider)
                                  .build()) {
            final BlockingWebClient client =
                    WebClient.builder("https://" + hostname + ':' + port)
                             .factory(factory)
                             .build()
                             .blocking();
            assertThat(client.get("/").status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void defaultTrustedCertificates() {
        final TlsProvider tlsProvider =
                TlsProvider.builder()
                           .keyPair("foo.com", clientCertFoo.tlsKeyPair())
                           .trustedCertificates(serverCertFoo.certificate())
                           .build();
        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .addressResolverGroupFactory(
                                          unused -> MockAddressResolverGroup.localhost())
                                  .tlsProvider(tlsProvider)
                                  .build()) {
            final BlockingWebClient client =
                    WebClient.builder("https://foo.com:" + fooServer.httpsPort())
                             .factory(factory)
                             .build()
                             .blocking();
            assertThat(client.get("/").status()).isEqualTo(HttpStatus.OK);
        }
    }

    static Stream<Arguments> simpleParameters() {
        return Stream.of(
                Arguments.of("foo.com", fooServer.httpsPort(),
                             clientCertFoo.tlsKeyPair(), serverCertFoo.certificate()),
                Arguments.of("bar.com", barServer.httpsPort(),
                             clientCertBar.tlsKeyPair(), serverCertBar.certificate()));
    }

    @Test
    void simpleUsage_bar() {
        final TlsProvider tlsProvider =
                TlsProvider.builder()
                           .keyPair("bar.com", clientCertBar.tlsKeyPair())
                           .trustedCertificates("bar.com", serverCertBar.certificate())
                           .build();
        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .addressResolverGroupFactory(
                                          unused -> MockAddressResolverGroup.localhost())
                                  .tlsProvider(tlsProvider)
                                  .build()) {
            final BlockingWebClient client =
                    WebClient.builder("https://bar.com:" + barServer.httpsPort())
                             .factory(factory)
                             .build()
                             .blocking();
            assertThat(client.get("/").status()).isEqualTo(HttpStatus.OK);
        }
    }
}
