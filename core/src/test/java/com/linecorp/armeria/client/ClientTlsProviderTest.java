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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.internal.common.util.CertificateUtil;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerTlsConfig;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    static final SelfSignedCertificateExtension clientFooCert = new SelfSignedCertificateExtension("foo.com");
    @RegisterExtension
    static final SelfSignedCertificateExtension clientSubFooCert =
            new SelfSignedCertificateExtension("sub.foo.com");
    @RegisterExtension
    static final SelfSignedCertificateExtension clientBarCert = new SelfSignedCertificateExtension("bar.com");

    @RegisterExtension
    static final ServerExtension server0 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final TlsProvider tlsProvider =
                    TlsProvider.builder()
                               .keyPair(server0DefaultCert.tlsKeyPair())
                               .keyPair("foo.com", server0FooCert.tlsKeyPair())
                               .keyPair("*.foo.com", server0SubFooCert.tlsKeyPair())
                               .trustedCertificates(clientFooCert.certificate(), clientSubFooCert.certificate())
                               .build();

            final ServerTlsConfig tlsConfig = ServerTlsConfig.builder()
                                                             .clientAuth(ClientAuth.REQUIRE)
                                                             .build();
            sb.https(0)
              .tlsProvider(tlsProvider, tlsConfig)
              .service("/", (ctx, req) -> {
                  final String commonName = CertificateUtil.getHostname(ctx.sslSession());
                  return HttpResponse.of("default:" + commonName);
              })
              .virtualHost("foo.com")
              .service("/", (ctx, req) -> {
                  final String commonName = CertificateUtil.getHostname(ctx.sslSession());
                  return HttpResponse.of("foo:" + commonName);
              })
              .and()
              .virtualHost("sub.foo.com")
              .service("/", (ctx, req) -> {
                  final String commonName = CertificateUtil.getHostname(ctx.sslSession());
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
                               .keyPair(server1DefaultCert.tlsKeyPair())
                               .keyPair("bar.com", server1BarCert.tlsKeyPair())
                               .trustedCertificates(clientFooCert.certificate())
                               .build();
            final ServerTlsConfig tlsConfig = ServerTlsConfig.builder()
                                                             .clientAuth(ClientAuth.REQUIRE)
                                                             .build();

            sb.https(0)
              .tlsProvider(tlsProvider, tlsConfig)
              .service("/", (ctx, req) -> {
                  final String commonName = CertificateUtil.getHostname(ctx.sslSession());
                  return HttpResponse.of("default:" + commonName);
              })
              .virtualHost("bar.com")
              .service("/", (ctx, req) -> {
                  final String commonName = CertificateUtil.getHostname(ctx.sslSession());
                  return HttpResponse.of("virtual:" + commonName);
              });
        }
    };

    @RegisterExtension
    static final ServerExtension serverNoMtls = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final TlsProvider tlsProvider =
                    TlsProvider.builder()
                               .keyPair(server0DefaultCert.tlsKeyPair())
                               .keyPair("bar.com", server1BarCert.tlsKeyPair())
                               .build();

            sb.https(0)
              .tlsProvider(tlsProvider)
              .service("/", (ctx, req) -> {
                  final String commonName = CertificateUtil.getHostname(ctx.sslSession());
                  return HttpResponse.of("default:" + commonName);
              })
              .virtualHost("bar.com")
              .service("/", (ctx, req) -> {
                  final String commonName = CertificateUtil.getHostname(ctx.sslSession());
                  return HttpResponse.of("virtual:" + commonName);
              });
        }
    };

    @Test
    void testExactMatch() {
        final TlsProvider tlsProvider =
                TlsProvider.builder()
                           .keyPair("*.foo.com", clientFooCert.tlsKeyPair())
                           .keyPair("bar.com", clientBarCert.tlsKeyPair())
                           .keyPair(TlsKeyPair.of(clientFooCert.privateKey(),
                                                  clientFooCert.certificate()))
                           .trustedCertificates("foo.com", server0FooCert.certificate())
                           .trustedCertificates("bar.com", server1BarCert.certificate())
                           .trustedCertificates("sub.foo.com", server0SubFooCert.certificate())
                           .trustedCertificates(server0DefaultCert.certificate())
                           .build();

        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .tlsProvider(tlsProvider)
                                                  .meterRegistry(meterRegistry)
                                                  .addressResolverGroupFactory(
                                                          unused -> MockAddressResolverGroup.localhost())
                                                  .build()) {
            // clientFooCert should be chosen by TlsProvider.
            BlockingWebClient client = WebClient.builder("https://foo.com:" + server0.httpsPort())
                                                .factory(factory)
                                                .build()
                                                .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("foo:foo.com");
            client = WebClient.builder("https://sub.foo.com:" + server0.httpsPort())
                              .factory(factory)
                              .build()
                              .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("sub.foo:sub.foo.com");
            client = WebClient.builder("https://127.0.0.1:" + server0.httpsPort())
                              .factory(factory)
                              .build()
                              .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("default:localhost");

            await().untilAsserted(() -> {
                final Map<String, Double> metrics = MoreMeters.measureAll(meterRegistry);
                // Make sure that the metrics for the certificates generated from TlsProvider are exported.
                assertThat(metrics.get("armeria.client.tls.certificate.validity#value{hostname=foo.com}"))
                        .isEqualTo(1.0);
                assertThat(
                        metrics.get("armeria.client.tls.certificate.validity#value{hostname=sub.foo.com}"))
                        .isEqualTo(1.0);
            });
        }

        await().untilAsserted(() -> {
            final Map<String, Double> metrics = MoreMeters.measureAll(meterRegistry);
            // The metrics for the certificates should be closed when the associated connections are closed.
            assertThat(metrics.get("armeria.client.tls.certificate.validity#value{hostname=foo.com}"))
                    .isNull();
            assertThat(metrics.get("armeria.client.tls.certificate.validity#value{hostname=sub.foo.com}"))
                    .isNull();
        });
    }

    @Test
    void testWildcardMatch() {
        final TlsProvider tlsProvider =
                TlsProvider.builder()
                           .keyPair("foo.com", clientFooCert.tlsKeyPair())
                           .keyPair("*.foo.com", clientFooCert.tlsKeyPair())
                           .trustedCertificates(server0FooCert.certificate(),
                                                server0SubFooCert.certificate())
                           .build();

        try (
                ClientFactory factory = ClientFactory.builder()
                                                     .tlsProvider(tlsProvider)
                                                     .addressResolverGroupFactory(
                                                             unused -> MockAddressResolverGroup.localhost())
                                                     .build()) {
            // clientFooCert should be chosen by TlsProvider.
            BlockingWebClient client = WebClient.builder("https://foo.com:" + server0.httpsPort())
                                                .factory(factory)
                                                .build()
                                                .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("foo:foo.com");
            client = WebClient.builder("https://sub.foo.com:" + server0.httpsPort())
                              .factory(factory)
                              .build()
                              .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("sub.foo:sub.foo.com");
        }
    }

    @Test
    void testNoMtls() {
        final TlsProvider tlsProvider =
                TlsProvider.builder()
                           .keyPair("foo.com", clientFooCert.tlsKeyPair())
                           .trustedCertificates(server0DefaultCert.certificate(),
                                                server1BarCert.certificate())
                           .build();

        try (ClientFactory factory = ClientFactory.builder()
                                                  .tlsProvider(tlsProvider)
                                                  .addressResolverGroupFactory(
                                                          unused -> MockAddressResolverGroup.localhost())
                                                  .build()) {
            // clientFooCert should be chosen by TlsProvider.
            BlockingWebClient client = WebClient.builder("https://bar.com:" + serverNoMtls.httpsPort())
                                                .factory(factory)
                                                .build()
                                                .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("virtual:bar.com");

            client = WebClient.builder("https://127.0.0.1:" + serverNoMtls.httpsPort())
                              .factory(factory)
                              .build()
                              .blocking();
            assertThat(client.get("/").contentUtf8()).isEqualTo("default:localhost");
        }
    }

    @Test
    void disallowTlsProviderWhenTlsSettingsIsSet() {
        final TlsProvider tlsProvider =
                TlsProvider.of(TlsKeyPair.ofSelfSigned());

        assertThatThrownBy(() -> {
            ClientFactory.builder()
                         .tlsProvider(tlsProvider)
                         .tls(TlsKeyPair.ofSelfSigned());
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cannot configure TLS settings because a TlsProvider has been set.");

        assertThatThrownBy(() -> {
            ClientFactory.builder()
                         .tlsProvider(tlsProvider)
                         .tlsCustomizer(b -> {});
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cannot configure TLS settings because a TlsProvider has been set.");

        assertThatThrownBy(() -> {
            ClientFactory.builder()
                         .tlsProvider(tlsProvider)
                         .tlsNoVerify();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cannot configure TLS settings because a TlsProvider has been set.");

        assertThatThrownBy(() -> {
            ClientFactory.builder()
                         .tlsProvider(tlsProvider)
                         .tlsNoVerifyHosts("example.com");
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cannot configure TLS settings because a TlsProvider has been set.");

        assertThatThrownBy(() -> {
            ClientFactory.builder()
                         .tls(TlsKeyPair.ofSelfSigned())
                         .tlsProvider(tlsProvider);
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(
                  "Cannot configure the TlsProvider because static TLS settings have been set already.");
    }
}
