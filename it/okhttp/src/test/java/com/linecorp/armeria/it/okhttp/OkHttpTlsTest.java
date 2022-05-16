/*
 *  Copyright 2021 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.it.okhttp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.NetUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.tls.HandshakeCertificates;

class OkHttpTlsTest {

    @RegisterExtension
    @Order(1)
    static final SelfSignedCertificateExtension domainCert = new SelfSignedCertificateExtension("localhost");

    @RegisterExtension
    @Order(1)
    static final SelfSignedCertificateExtension ipCert = new SelfSignedCertificateExtension("127.0.0.1");

    @RegisterExtension
    @Order(2)
    static final ServerExtension domainCertServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .tls(domainCert.certificateFile(), domainCert.privateKeyFile());
        }
    };

    @RegisterExtension
    @Order(2)
    static final ServerExtension ipCertServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .tls(ipCert.certificateFile(), ipCert.privateKeyFile());
        }
    };

    private static OkHttpClient localhostClient;
    private static OkHttpClient ipClient;

    @BeforeAll
    static void setupClient() {
        final HandshakeCertificates localhost = new HandshakeCertificates.Builder()
                .addTrustedCertificate(domainCert.certificate())
                .build();
        localhostClient = new OkHttpClient.Builder()
                .sslSocketFactory(localhost.sslSocketFactory(), localhost.trustManager())
                .build();
        final HandshakeCertificates ip = new HandshakeCertificates.Builder()
                .addTrustedCertificate(ipCert.certificate())
                .build();
        ipClient = new OkHttpClient.Builder()
                .sslSocketFactory(ip.sslSocketFactory(), ip.trustManager())
                .build();
    }

    @ParameterizedTest
    @ArgumentsSource(ClientProvider.class)
    void okhttpTls(OkHttpClient client, int serverPort, String address) throws Exception {
        final Request request = new Request.Builder()
                .url("https://" + address + ':' + serverPort + '/')
                .build();

        try (Response response = client.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
        }
    }

    static class ClientProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            final List<String> addresses = new ArrayList<>();
            addresses.add("localhost");
            addresses.add("127.0.0.1");
            // Test ipv6 if available
            if (NetUtil.LOCALHOST instanceof Inet6Address) {
                // This is in the certificate.
                addresses.add("[::1]");
                // This isn't in the certificate, but it still works since OkHttp normalizes when verifying.
                addresses.add("[0:0:0:0:0:0:0:1]");
            }
            return Stream
                    .of(Arguments.of(localhostClient, domainCertServer.httpsPort()),
                        Arguments.of(ipClient, ipCertServer.httpsPort()))
                    .map(Arguments::get)
                    .flatMap(item -> addresses.stream()
                                              .map(address -> Arguments.of(item[0], item[1], address)));
        }
    }
}
