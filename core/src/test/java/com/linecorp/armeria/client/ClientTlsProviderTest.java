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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.internal.common.util.CertificateUtil;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.ssl.ClientAuth;

class ClientTlsProviderTest {

    @RegisterExtension
    static final SelfSignedCertificateExtension server0DefaultCert = new SelfSignedCertificateExtension();
    @RegisterExtension
    static final SelfSignedCertificateExtension server0FooCert = new SelfSignedCertificateExtension(
            "foo.com");
    @RegisterExtension
    static final SelfSignedCertificateExtension server0SubFooCert = new SelfSignedCertificateExtension(
            "sub.foo.com");
    @RegisterExtension
    static final SelfSignedCertificateExtension server1DefaultCert = new SelfSignedCertificateExtension();
    @RegisterExtension
    static final SelfSignedCertificateExtension server1BarCert = new SelfSignedCertificateExtension("bar.com");
    @RegisterExtension
    static final SelfSignedCertificateExtension clientFooCert = new SelfSignedCertificateExtension();
    @RegisterExtension
    static final SelfSignedCertificateExtension clientBarCert = new SelfSignedCertificateExtension();

    @RegisterExtension
    static final ServerExtension server0 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final TlsProvider tlsProvider =
                    TlsProvider.builder()
                               .setDefault(TlsKeyPair.of(server0DefaultCert.privateKey(),
                                                         server0DefaultCert.certificate()))
                               .set("foo.com", TlsKeyPair.of(server0FooCert.privateKey(),
                                                             server0FooCert.certificate()))
                               .set("*.foo.com", TlsKeyPair.of(server0SubFooCert.privateKey(),
                                                               server0SubFooCert.certificate()))
                               .tlsCustomizer(b -> {
                                   b.clientAuth(ClientAuth.REQUIRE)
                                    .trustManager(clientFooCert.certificate());
                               })
                               .build();

            sb.https(0)
              .tlsProvider(tlsProvider)
              .service("/", (ctx, req) -> {
                  final String commonName = CertificateUtil.getCommonName(ctx.sslSession());
                  return HttpResponse.of("default:" + commonName);
              })
              .virtualHost("foo.com")
              .service("/", (ctx, req) -> {
                  final String commonName = CertificateUtil.getCommonName(ctx.sslSession());
                  return HttpResponse.of("foo:" + commonName);
              })
              .and()
              .virtualHost("sub.foo.com")
              .service("/", (ctx, req) -> {
                  final String commonName = CertificateUtil.getCommonName(ctx.sslSession());
                  return HttpResponse.of("sub.foo:" + commonName);
              });
        }
    };

    @RegisterExtension
    static final ServerExtension server1 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final TlsProvider tlsProvider =
                    TlsProvider.builder()
                               .setDefault(TlsKeyPair.of(server1DefaultCert.privateKey(),
                                                         server1DefaultCert.certificate()))
                               .set("bar.com", TlsKeyPair.of(server1BarCert.privateKey(),
                                                             server1BarCert.certificate()))
                               .tlsCustomizer(b -> {
                                   b.clientAuth(ClientAuth.REQUIRE)
                                    .trustManager(clientFooCert.certificate());
                               })
                               .build();

            sb.https(0)
              .tlsProvider(tlsProvider)
              .service("/", (ctx, req) -> {
                  final String commonName = CertificateUtil.getCommonName(ctx.sslSession());
                  return HttpResponse.of("default:" + commonName);
              })
              .virtualHost("bar.com")
              .service("/", (ctx, req) -> {
                  final String commonName = CertificateUtil.getCommonName(ctx.sslSession());
                  return HttpResponse.of("virtual:" + commonName);
              });
        }
    };

    @Test
    void testExactMatch() {
        final TlsProvider tlsProvider =
                TlsProvider.builder()
                           .set("*.foo.com", TlsKeyPair.of(clientFooCert.privateKey(),
                                                           clientFooCert.certificate()))
                           .set("bar.com", TlsKeyPair.of(clientBarCert.privateKey(),
                                                         clientBarCert.certificate()))
                           .setDefault(TlsKeyPair.of(clientFooCert.privateKey(),
                                                     clientFooCert.certificate()))
                           .tlsCustomizer(customizer -> {
                               customizer.trustManager(
                                       server0DefaultCert.certificate(),
                                       server0FooCert.certificate(),
                                       server0SubFooCert.certificate(),
                                       server1BarCert.certificate());
                           })
                           .build();

        try (ClientFactory factory = ClientFactory.builder()
                                                        .addressResolverGroupFactory(
                                                                unused -> MockAddressResolverGroup.localhost())
                                                        .build()) {
            // clientFooCert should be chosen by TlsProvider.
            BlockingWebClient client = WebClient.builder("https://foo.com:" + server0.httpsPort())
                                                .tlsProvider(tlsProvider)
                                                .factory(factory)
                                                .build()
                                                .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("foo:foo.com");
            client = WebClient.builder("https://sub.foo.com:" + server0.httpsPort())
                              .tlsProvider(tlsProvider)
                              .factory(factory)
                              .build()
                              .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("sub.foo:sub.foo.com");
            client = WebClient.builder("https://127.0.0.1:" + server0.httpsPort())
                              .tlsProvider(tlsProvider)
                              .factory(factory)
                              .build()
                              .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("default:localhost");
        }
    }

    @Test
    void testWildcardMatch() {
        final TlsProvider tlsProvider =
                TlsProvider.builder()
                           .set("*.foo.com", TlsKeyPair.of(clientFooCert.privateKey(),
                                                           clientFooCert.certificate()))
                           .tlsCustomizer(customizer -> {
                               customizer.trustManager(server0FooCert.certificate(),
                                                       server0SubFooCert.certificate());
                           })
                           .build();

        try (ClientFactory factory = ClientFactory.builder()
                                                        .addressResolverGroupFactory(
                                                                unused -> MockAddressResolverGroup.localhost())
                                                        .build()) {
            // clientFooCert should be chosen by TlsProvider.
            BlockingWebClient client = WebClient.builder("https://foo.com:" + server0.httpsPort())
                                                .tlsProvider(tlsProvider)
                                                .factory(factory)
                                                .build()
                                                .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("foo:foo.com");
            client = WebClient.builder("https://sub.foo.com:" + server0.httpsPort())
                              .tlsProvider(tlsProvider)
                              .factory(factory)
                              .build()
                              .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("sub.foo:sub.foo.com");
        }
    }
}
