/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.common.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.cert.CertificateException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsPeerVerifier;
import com.linecorp.armeria.common.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.internal.common.util.SslContextUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerTlsConfig;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.junit5.server.SignedCertificateExtension;

import io.netty.handler.ssl.ClientAuth;

class TlsSpecPerRequestTest {

    @Order(0)
    @RegisterExtension
    static final SelfSignedCertificateExtension clientRootCert = new SelfSignedCertificateExtension();

    @Order(1)
    @RegisterExtension
    static final SelfSignedCertificateExtension serverRootCert = new SelfSignedCertificateExtension();

    @Order(2)
    @RegisterExtension
    static final SignedCertificateExtension serverIntermediateCert =
            new SignedCertificateExtension(serverRootCert);

    @Order(3)
    @RegisterExtension
    static final SignedCertificateExtension serverLeafCert =
            new SignedCertificateExtension(serverIntermediateCert);

    @Order(4)
    @RegisterExtension
    static final SignedCertificateExtension clientIntermediateCert =
            new SignedCertificateExtension(clientRootCert);

    @Order(5)
    @RegisterExtension
    static final SignedCertificateExtension clientLeaf =
            new SignedCertificateExtension(clientIntermediateCert);

    @Order(6)
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
            final TlsProvider serverTlsProvider =
                    TlsProvider.builder().keyPair(TlsKeyPair.of(serverLeafCert.privateKey(),
                                                                serverLeafCert.certificate(),
                                                                serverIntermediateCert.certificate()))
                               .trustedCertificates(clientRootCert.certificate())
                               .build();
            sb.tlsProvider(serverTlsProvider, ServerTlsConfig.builder()
                                                             .clientAuth(ClientAuth.REQUIRE)
                                                             .build());
        }
    };

    @Test
    void mtlsSuccess() throws Exception {
        final TlsKeyPair clientTlsKeyPair =
                TlsKeyPair.of(clientLeaf.privateKey(),
                              clientLeaf.certificate(),
                              clientIntermediateCert.certificate());
        final ClientTlsSpec clientTlsSpec = ClientTlsSpec.builder()
                                                         .tlsKeyPair(clientTlsKeyPair)
                                                         .trustedCertificates(serverRootCert.certificate())
                                                         .build();
        final AggregatedHttpResponse res = server.blockingWebClient()
                                                 .prepare()
                                                 .clientTlsSpec(clientTlsSpec)
                                                 .get("/")
                                                 .execute();
        assertThat(res.status().code()).isEqualTo(200);
    }

    @Test
    void certTrustFails() throws Exception {
        final TlsKeyPair clientTlsKeyPair =
                TlsKeyPair.of(clientLeaf.privateKey(),
                              clientLeaf.certificate(),
                              clientIntermediateCert.certificate());
        final ClientTlsSpec clientTlsSpec = ClientTlsSpec.builder()
                                                         .tlsKeyPair(clientTlsKeyPair)
                                                         .trustedCertificates(clientRootCert.certificate())
                                                         .build();
        assertThatThrownBy(() -> server.blockingWebClient()
                                       .prepare()
                                       .clientTlsSpec(clientTlsSpec)
                                       .get("/")
                                       .execute())
                .isInstanceOf(UnprocessedRequestException.class)
                .hasCauseInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void alwaysTrustingVerifier() throws Exception {
        final TlsKeyPair clientTlsKeyPair =
                TlsKeyPair.of(clientLeaf.privateKey(),
                              clientLeaf.certificate(),
                              clientIntermediateCert.certificate());
        final ClientTlsSpec clientTlsSpec = ClientTlsSpec.builder()
                                                         .tlsKeyPair(clientTlsKeyPair)
                                                         .verifierFactories(TlsPeerVerifierFactory.noVerify())
                                                         .build();
        final AggregatedHttpResponse res = server.blockingWebClient()
                                                 .prepare()
                                                 .clientTlsSpec(clientTlsSpec)
                                                 .get("/")
                                                 .execute();
        assertThat(res.status().code()).isEqualTo(200);
    }

    @Test
    void peerVerifierFails() throws Exception {
        final TlsKeyPair clientTlsKeyPair =
                TlsKeyPair.of(clientLeaf.privateKey(),
                              clientLeaf.certificate(),
                              clientIntermediateCert.certificate());
        final ClientTlsSpec clientTlsSpec =
                ClientTlsSpec.builder()
                             .tlsKeyPair(clientTlsKeyPair)
                             .trustedCertificates(serverRootCert.certificate())
                             .verifierFactories(AlwaysThrowing.INSTANCE)
                             .build();
        assertThatThrownBy(() -> {
            server.blockingWebClient()
                  .prepare()
                  .clientTlsSpec(clientTlsSpec)
                  .get("/")
                  .execute();
        }).isInstanceOf(UnprocessedRequestException.class)
          .hasCauseInstanceOf(SSLHandshakeException.class)
          .hasRootCause(AlwaysThrowing.certificateException);
    }

    static Stream<Arguments> peerVerifier_args() {
        return Stream.of(Arguments.of(ImmutableList.of(AlwaysThrowing.INSTANCE,
                                                       TlsPeerVerifierFactory.noVerify()),
                                      null),
                         Arguments.of(ImmutableList.of(TlsPeerVerifierFactory.noVerify(),
                                                       AlwaysThrowing.INSTANCE),
                                      AlwaysThrowing.certificateException));
    }

    @ParameterizedTest
    @MethodSource("peerVerifier_args")
    void peerVerifier(List<TlsPeerVerifierFactory> verifierFactories, Throwable rootCause) {
        final TlsKeyPair clientTlsKeyPair =
                TlsKeyPair.of(clientLeaf.privateKey(),
                              clientLeaf.certificate(),
                              clientIntermediateCert.certificate());
        final ClientTlsSpec clientTlsSpec =
                ClientTlsSpec.builder()
                             .tlsKeyPair(clientTlsKeyPair)
                             .verifierFactories(verifierFactories)
                             .build();

        if (rootCause == null) {
            assertThat(server.blockingWebClient()
                             .prepare()
                             .clientTlsSpec(clientTlsSpec)
                             .get("/")
                             .execute().status().code()).isEqualTo(200);
        } else {
            assertThatThrownBy(() -> {
                server.blockingWebClient()
                      .prepare()
                      .clientTlsSpec(clientTlsSpec)
                      .get("/")
                      .execute();
            }).isInstanceOf(UnprocessedRequestException.class)
              .hasCauseInstanceOf(SSLHandshakeException.class)
              .hasRootCause(rootCause);
        }
    }

    @Test
    void delegatingVerifier() throws Exception {
        final Deque<String> q = new ArrayDeque<>();
        final TlsKeyPair clientTlsKeyPair =
                TlsKeyPair.of(clientLeaf.privateKey(),
                              clientLeaf.certificate(),
                              clientIntermediateCert.certificate());
        final ClientTlsSpec clientTlsSpec =
                ClientTlsSpec.builder()
                             .tlsKeyPair(clientTlsKeyPair)
                             .trustedCertificates(serverRootCert.certificate())
                             .verifierFactories(new DelegatingFactory(() -> q.add("c")),
                                                new DelegatingFactory(() -> q.add("b")),
                                                new DelegatingFactory(() -> q.add("a")))
                             .build();
        final AggregatedHttpResponse res = server.blockingWebClient()
                                                 .prepare()
                                                 .clientTlsSpec(clientTlsSpec)
                                                 .get("/")
                                                 .execute();
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(q).containsExactly("a", "b", "c");
    }

    @Test
    void tlsCustomizerAppliedFromFactory() throws Exception {
        final TlsKeyPair clientTlsKeyPair =
                TlsKeyPair.of(clientLeaf.privateKey(),
                              clientLeaf.certificate(),
                              clientIntermediateCert.certificate());
        final AtomicBoolean customizerCalled = new AtomicBoolean(false);
        try (ClientFactory factory = ClientFactory
                .builder()
                .tlsCustomizer(sslCtxBuilder -> customizerCalled.set(true))
                .build()) {
            final ClientTlsSpec clientTlsSpec = ClientTlsSpec.builder()
                                                             .tlsKeyPair(clientTlsKeyPair)
                                                             .trustedCertificates(serverRootCert.certificate())
                                                             .build();

            final AggregatedHttpResponse res = server.blockingWebClient(cb -> cb.factory(factory))
                                                     .prepare()
                                                     .clientTlsSpec(clientTlsSpec)
                                                     .get("/")
                                                     .execute();
            assertThat(res.status().code()).isEqualTo(200);
            assertThat(customizerCalled).isTrue();
        }
    }

    @Test
    void alpnOverwritten() throws Exception {
        final TlsKeyPair clientTlsKeyPair =
                TlsKeyPair.of(clientLeaf.privateKey(),
                              clientLeaf.certificate(),
                              clientIntermediateCert.certificate());
        final ClientTlsSpec clientTlsSpec = ClientTlsSpec.builder()
                                                         .tlsKeyPair(clientTlsKeyPair)
                                                         .trustedCertificates(serverRootCert.certificate())
                                                         .build();
        assertThat(clientTlsSpec.alpnProtocols())
                .containsExactlyElementsOf(SslContextUtil.DEFAULT_ALPN_PROTOCOLS);
        // if alpn isn't set, this test will fail as the negotiated alpn will be h2
        final AggregatedHttpResponse res = WebClient.of(SessionProtocol.H1, server.httpsEndpoint())
                                                    .blocking()
                                                    .prepare()
                                                    .clientTlsSpec(clientTlsSpec)
                                                    .get("/")
                                                    .execute();
        assertThat(res.status().code()).isEqualTo(200);
    }

    @Test
    void ctxOverwritten() throws Exception {
        final TlsKeyPair clientTlsKeyPair =
                TlsKeyPair.of(clientLeaf.privateKey(),
                              clientLeaf.certificate(),
                              clientIntermediateCert.certificate());
        final ClientTlsSpec clientTlsSpec = ClientTlsSpec.builder()
                                                         .tlsKeyPair(clientTlsKeyPair)
                                                         .trustedCertificates(serverRootCert.certificate())
                                                         .build();
        final AggregatedHttpResponse res =
                server.blockingWebClient(cb -> cb.decorator((delegate, ctx, req) -> {
                          ctx.setClientTlsSpec(clientTlsSpec);
                          return delegate.execute(ctx, req);
                      }))
                      .get("/");
        assertThat(res.status().code()).isEqualTo(200);
    }

    private static final class AlwaysThrowing implements TlsPeerVerifierFactory {

        private static final CertificateException certificateException =
                new CertificateException("my-exception");
        private static final AlwaysThrowing INSTANCE = new AlwaysThrowing();

        @Override
        public TlsPeerVerifier create(TlsPeerVerifier delegate) {
            return (certs, authType, engine) -> {
                throw certificateException;
            };
        }
    }

    private static final class DelegatingFactory implements TlsPeerVerifierFactory {

        private final Runnable runnable;

        DelegatingFactory(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public TlsPeerVerifier create(TlsPeerVerifier delegate) {
            return (chain, authType, engine) -> {
                runnable.run();
                delegate.verify(chain, authType, engine);
            };
        }
    }
}
