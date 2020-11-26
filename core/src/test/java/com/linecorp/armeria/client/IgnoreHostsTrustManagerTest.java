/*
 * Copyright 2020 LINE Corporation
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

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509ExtendedTrustManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class IgnoreHostsTrustManagerTest {

    private static final String[] EMPTY_STRINGS = new String[0];
    private static final X509Certificate[] EMPTY_CERTIFICATES = new X509Certificate[0];

    private static int httpsPort;
    private static Socket defaultSocket;
    private static SSLEngine defaultSslEngine;
    private static X509Certificate[] defaultCerts;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of());
            sb.tlsSelfSigned();
        }
    };

    @BeforeAll
    static void init() {
        httpsPort = server.httpsPort();
        defaultCerts = EMPTY_CERTIFICATES;
        defaultSocket = new Socket();
        defaultSslEngine = new MockSSLEngine("localhost", 0);
    }

    @AfterAll
    static void destroy() throws IOException {
        defaultSocket.close();
    }

    @Test
    void testCreate() {
        assertThat(IgnoreHostsTrustManager.of(ImmutableSet.of("localhost"))).isNotNull();
    }

    @Test
    void testCheckServerTrustedWithSocket() throws Exception {
        final Socket socket = new Socket("localhost", httpsPort);
        final X509Certificate[] certs = EMPTY_CERTIFICATES;
        final MockTrustManager delegate = new MockTrustManager();
        IgnoreHostsTrustManager tm;

        // if host is ignored, the check is not delegated, therefore delegate.received is false
        tm = new IgnoreHostsTrustManager(delegate, ImmutableSet.of("localhost"));
        tm.checkServerTrusted(certs, "", socket);
        assertThat(delegate.received).isFalse();

        // if host is not ignored, the check is delegated
        tm = new IgnoreHostsTrustManager(delegate, ImmutableSet.of());
        tm.checkServerTrusted(certs, "", socket);
        assertThat(delegate.received).isTrue();

        socket.close();
    }

    @Test
    void testCheckServerTrustedWithSslEngine() throws Exception {
        final MockSSLEngine sslEngine = new MockSSLEngine("localhost", httpsPort);
        final X509Certificate[] certs = EMPTY_CERTIFICATES;
        final MockTrustManager delegate = new MockTrustManager();
        IgnoreHostsTrustManager tm;

        // if host is ignored, the check is not delegated, therefore delegate.received is false
        tm = new IgnoreHostsTrustManager(delegate, ImmutableSet.of("localhost"));
        tm.checkServerTrusted(certs, "", sslEngine);
        assertThat(delegate.received).isFalse();

        // if host is not ignored, the check is delegated
        tm = new IgnoreHostsTrustManager(delegate, ImmutableSet.of());
        tm.checkServerTrusted(certs, "", sslEngine);
        assertThat(delegate.received).isTrue();
    }

    @Test
    void testGetAcceptedIssuers() {
        final MockTrustManager delegate = new MockTrustManager();
        final IgnoreHostsTrustManager tm = new IgnoreHostsTrustManager(delegate, ImmutableSet.of());
        assertThat(tm.getAcceptedIssuers()).isEqualTo(delegate.certificates);
    }

    @Test
    void testCheckServerTrustedWithAuthType() {
        final IgnoreHostsTrustManager tm = IgnoreHostsTrustManager.of(ImmutableSet.of());
        assertThatThrownBy(() -> tm.checkServerTrusted(defaultCerts, ""))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testCheckClientTrustedWithSocket() {
        final IgnoreHostsTrustManager tm = IgnoreHostsTrustManager.of(ImmutableSet.of());
        assertThatThrownBy(() -> tm.checkClientTrusted(defaultCerts, "", defaultSocket))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testCheckClientTrustedWithSslEngine() {
        final IgnoreHostsTrustManager tm = IgnoreHostsTrustManager.of(ImmutableSet.of());
        assertThatThrownBy(() -> tm.checkClientTrusted(defaultCerts, "", defaultSslEngine))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testCheckClientTrustedWithAuthType() {
        final IgnoreHostsTrustManager tm = IgnoreHostsTrustManager.of(ImmutableSet.of());
        assertThatThrownBy(() -> tm.checkClientTrusted(defaultCerts, ""))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static final class MockSSLEngine extends SSLEngine {

        private MockSSLEngine(String peerHost, int port) {
            super(peerHost, port);
        }

        @Override
        public SSLEngineResult wrap(ByteBuffer[] byteBuffers, int i, int i1, ByteBuffer byteBuffer) {
            return null;
        }

        @Override
        public SSLEngineResult unwrap(ByteBuffer byteBuffer, ByteBuffer[] byteBuffers, int i, int i1) {
            return null;
        }

        @Override
        public Runnable getDelegatedTask() {
            return null;
        }

        @Override
        public void closeInbound() {}

        @Override
        public boolean isInboundDone() {
            return false;
        }

        @Override
        public void closeOutbound() {}

        @Override
        public boolean isOutboundDone() {
            return false;
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return EMPTY_STRINGS;
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return EMPTY_STRINGS;
        }

        @Override
        public void setEnabledCipherSuites(String[] strings) {}

        @Override
        public String[] getSupportedProtocols() {
            return EMPTY_STRINGS;
        }

        @Override
        public String[] getEnabledProtocols() {
            return EMPTY_STRINGS;
        }

        @Override
        public void setEnabledProtocols(String[] strings) {
        }

        @Override
        public SSLSession getSession() {
            return null;
        }

        @Override
        public void beginHandshake() {}

        @Override
        public SSLEngineResult.HandshakeStatus getHandshakeStatus() {
            return null;
        }

        @Override
        public void setUseClientMode(boolean b) {}

        @Override
        public boolean getUseClientMode() {
            return false;
        }

        @Override
        public void setNeedClientAuth(boolean b) {
        }

        @Override
        public boolean getNeedClientAuth() {
            return false;
        }

        @Override
        public void setWantClientAuth(boolean b) {}

        @Override
        public boolean getWantClientAuth() {
            return false;
        }

        @Override
        public void setEnableSessionCreation(boolean b) {}

        @Override
        public boolean getEnableSessionCreation() {
            return false;
        }
    }

    private static final class MockTrustManager extends X509ExtendedTrustManager {

        private boolean received;
        private final X509Certificate[] certificates = EMPTY_CERTIFICATES;

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String authType, Socket socket) {
            received = true;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String authType,
                                       SSLEngine sslEngine) {
            received = true;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String autyType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String authType, Socket socket) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String authType,
                                       SSLEngine sslEngine) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String authType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return certificates;
        }
    }
}
