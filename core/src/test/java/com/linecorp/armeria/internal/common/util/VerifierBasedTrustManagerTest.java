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

package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.common.TlsPeerVerifier;
import com.linecorp.armeria.common.TlsPeerVerifierFactory;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;

import io.netty.util.internal.EmptyArrays;

class VerifierBasedTrustManagerTest {

    @Order(0)
    @RegisterExtension
    static final SelfSignedCertificateExtension cert = new SelfSignedCertificateExtension();

    @Test
    void alwaysTrustingVerifier() throws Exception {
        final SSLEngine sslEngine = sslEngine("127.0.0.1");
        final ClientTlsSpec clientTlsSpec = ClientTlsSpec.builder()
                                                         .tlsKeyPair(cert.tlsKeyPair())
                                                         .verifierFactories(TlsPeerVerifierFactory.noVerify())
                                                         .build();

        final X509ExtendedTrustManager trustManager = SslContextUtil.toTrustManager(
                clientTlsSpec, new AlwaysTrustServerManager());
        final X509Certificate[] certs = { cert.certificate() };

        // Should not throw - noVerify() accepts any certificate
        trustManager.checkServerTrusted(certs, "", sslEngine);
    }

    @Test
    void peerVerifierFails() throws Exception {
        final SSLEngine sslEngine = sslEngine("127.0.0.1");
        final ClientTlsSpec clientTlsSpec =
                ClientTlsSpec.builder()
                             .tlsKeyPair(cert.tlsKeyPair())
                             .verifierFactories(AlwaysThrowing.INSTANCE)
                             .build();
        assertThatThrownBy(() -> {
            final X509ExtendedTrustManager trustManager = SslContextUtil.toTrustManager(
                    clientTlsSpec, new AlwaysTrustServerManager());
            final X509Certificate[] certs = { cert.certificate()};
            trustManager.checkServerTrusted(certs, "", sslEngine);
        }).isEqualTo(AlwaysThrowing.certificateException);
    }

    @Test
    void peerVerifierWithSuccessfulChain() throws Exception {
        final SSLEngine sslEngine = sslEngine("127.0.0.1");
        final ClientTlsSpec clientTlsSpec =
                ClientTlsSpec.builder()
                             .tlsKeyPair(cert.tlsKeyPair())
                             .verifierFactories(AlwaysThrowing.INSTANCE,
                                                TlsPeerVerifierFactory.noVerify())
                             .build();

        // Should not throw - noVerify() comes after the throwing one and accepts
        final X509ExtendedTrustManager trustManager = SslContextUtil.toTrustManager(
                clientTlsSpec, new AlwaysTrustServerManager());
        final X509Certificate[] certs = { cert.certificate() };
        trustManager.checkServerTrusted(certs, "", sslEngine);
    }

    @Test
    void peerVerifierWithFailingChain() throws Exception {
        final SSLEngine sslEngine = sslEngine("127.0.0.1");
        final ClientTlsSpec clientTlsSpec =
                ClientTlsSpec.builder()
                             .tlsKeyPair(cert.tlsKeyPair())
                             .verifierFactories(TlsPeerVerifierFactory.noVerify(),
                                                AlwaysThrowing.INSTANCE)
                             .build();

        assertThatThrownBy(() -> {
            final X509ExtendedTrustManager trustManager = SslContextUtil.toTrustManager(
                    clientTlsSpec, new AlwaysTrustServerManager());
            final X509Certificate[] certs = { cert.certificate() };
            trustManager.checkServerTrusted(certs, "", sslEngine);
        }).isEqualTo(AlwaysThrowing.certificateException);
    }

    @Test
    void delegatingVerifier() throws Exception {
        final Deque<String> q = new ArrayDeque<>();
        final SSLEngine sslEngine = sslEngine("127.0.0.1");
        final ClientTlsSpec clientTlsSpec =
                ClientTlsSpec.builder()
                             .tlsKeyPair(cert.tlsKeyPair())
                             .verifierFactories(new DelegatingFactory(() -> q.add("c")),
                                                new DelegatingFactory(() -> q.add("b")),
                                                new DelegatingFactory(() -> q.add("a")))
                             .build();

        final X509ExtendedTrustManager trustManager = SslContextUtil.toTrustManager(
                clientTlsSpec, new AlwaysTrustServerManager());
        final X509Certificate[] certs = { cert.certificate() };

        trustManager.checkServerTrusted(certs, "", sslEngine);

        // Verify the delegation order (last factory is called first)
        assertThat(q).containsExactly("a", "b", "c");
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

    private static final class AlwaysTrustServerManager extends X509ExtendedTrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return EmptyArrays.EMPTY_X509_CERTIFICATES;
        }
    }

    private static SSLEngine sslEngine(String peerHost) {
        final SSLEngine sslEngine = mock(SSLEngine.class);
        Mockito.lenient().when(sslEngine.getPeerHost()).thenReturn(peerHost);
        return sslEngine;
    }
}
