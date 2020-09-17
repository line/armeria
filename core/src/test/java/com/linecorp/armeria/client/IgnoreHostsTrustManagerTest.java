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

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.HashSet;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerPort;

import io.netty.handler.ssl.util.SelfSignedCertificate;

class IgnoreHostsTrustManagerTest {

    private static int port;
    private static Server server;
    private static Socket defaultSocket;
    private static SSLEngine defaultSslEngine;
    private static X509Certificate[] defaultCerts;

    @BeforeAll
    static void init() throws NoSuchAlgorithmException {
        defaultCerts = new X509Certificate[0];
        defaultSocket = new Socket();
        defaultSslEngine = SSLContext.getDefault().createSSLEngine();
        try {
            final SelfSignedCertificate ssc = new SelfSignedCertificate("localhost");
            server = Server.builder()
                    .service("/", (ctx, req) -> HttpResponse.of())
                    .tls(ssc.certificate(), ssc.privateKey())
                    .build();
            server.start().get();
            port = server.activePorts().values().stream()
                    .filter(ServerPort::hasHttps).findAny().get().localAddress()
                    .getPort();
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    @AfterAll
    static void destroy() throws IOException {
        defaultSocket.close();
        defaultSslEngine.closeOutbound();
        defaultSslEngine.closeInbound();
        server.stop();
    }

    @Test
    void testCreate() {
        assertThat(IgnoreHostsTrustManager.of("localhost")).isNotNull();
    }

    @Test
    void testCheckServerTrusted() throws Exception {
        final Socket socket = new Socket("localhost", port);
        final X509Certificate[] certs = new X509Certificate[0];
        final MockTrustManager delegate = new MockTrustManager();
        IgnoreHostsTrustManager tm;

        tm = new IgnoreHostsTrustManager(delegate, new HashSet<>(singletonList("localhost")));
        tm.checkServerTrusted(certs, "", socket);
        assertThat(delegate.received).isFalse();

        tm = new IgnoreHostsTrustManager(delegate, new HashSet<>());
        tm.checkServerTrusted(certs, "", socket);
        assertThat(delegate.received).isTrue();

        socket.close();
    }

    @Test
    void testCheckServerTrusted1() throws Exception {
        final SSLEngine sslEngine = SSLContext.getDefault().createSSLEngine("localhost", port);
        final X509Certificate[] certs = new X509Certificate[0];
        final MockTrustManager delegate = new MockTrustManager();
        IgnoreHostsTrustManager tm;

        tm = new IgnoreHostsTrustManager(delegate, new HashSet<>(singletonList("localhost")));
        tm.checkServerTrusted(certs, "", sslEngine);
        assertThat(delegate.received).isFalse();

        tm = new IgnoreHostsTrustManager(delegate, new HashSet<>());
        tm.checkServerTrusted(certs, "", sslEngine);
        assertThat(delegate.received).isTrue();

        sslEngine.closeOutbound();
        sslEngine.closeInbound();
    }

    @Test
    void testGetAcceptedIssuers() {
        final MockTrustManager delegate = new MockTrustManager();
        final IgnoreHostsTrustManager tm = new IgnoreHostsTrustManager(delegate, new HashSet<>());
        assertThat(tm.getAcceptedIssuers()).isEqualTo(delegate.certificates);
    }

    @Test
    void testCheckServerTrusted2() {
        final IgnoreHostsTrustManager tm = IgnoreHostsTrustManager.of();
        assertThatThrownBy(() -> tm.checkServerTrusted(defaultCerts, ""))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testCheckClientTrusted() {
        final IgnoreHostsTrustManager tm = IgnoreHostsTrustManager.of();
        assertThatThrownBy(() -> tm.checkClientTrusted(defaultCerts, "", defaultSocket))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testCheckClientTrusted1() {
        final IgnoreHostsTrustManager tm = IgnoreHostsTrustManager.of();
        assertThatThrownBy(() -> tm.checkClientTrusted(defaultCerts, "", defaultSslEngine))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testCheckClientTrusted2() {
        final IgnoreHostsTrustManager tm = IgnoreHostsTrustManager.of();
        assertThatThrownBy(() -> tm.checkClientTrusted(defaultCerts, ""))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    static class MockTrustManager extends X509ExtendedTrustManager {

        boolean received;
        X509Certificate[] certificates = new X509Certificate[0];

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s, Socket socket) {
            received = true;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine) {
            received = true;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s, Socket socket) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) {
            throw new UnsupportedOperationException();
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return certificates;
        }
    }
}
