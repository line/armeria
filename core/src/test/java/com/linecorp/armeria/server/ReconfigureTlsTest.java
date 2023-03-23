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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ReconfigureTlsTest {

    private static final AtomicReference<X509Certificate> sslContextRef = new AtomicReference<>();

    private static final SelfSignedCertificate oldCert;

    static {
        try {
            oldCert = new SelfSignedCertificate(Date.from(Instant.parse("2022-01-01T00:00:00.00Z")),
                                                Date.from(Instant.now().plus(10, ChronoUnit.DAYS)));
        } catch (CertificateException e) {
            throw new RuntimeException(e);
        }
    }

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.https(0)
              .tls(oldCert.certificate(), oldCert.privateKey())
              .service("/", (ctx, req) -> {
                  sslContextRef.set((X509Certificate) ctx.sslSession().getLocalCertificates()[0]);
                  return HttpResponse.of("OK");
              });
        }
    };

    @Test
    void shouldUpdateTlsSettings() throws CertificateException {
        final BlockingWebClient client0 =
                WebClient.builder(server.httpsUri())
                         .factory(ClientFactory.builder()
                                               .tlsCustomizer(sslContextBuilder -> {
                                                   sslContextBuilder.trustManager(oldCert.certificate());
                                               }).build())
                         .build()
                         .blocking();

        final AggregatedHttpResponse response = client0.get("/");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(sslContextRef.get().getNotBefore()).isEqualTo(oldCert.cert().getNotBefore());

        final SelfSignedCertificate newCert =
                new SelfSignedCertificate(Date.from(Instant.parse("2023-01-01T00:00:00.00Z")),
                                          Date.from(Instant.now().plus(10, ChronoUnit.DAYS)));
        //noinspection resource
        server.server().reconfigure(sb -> {
            sb.tls(newCert.certificate(), newCert.privateKey())
              .service("/", (ctx, req) -> {
                  sslContextRef.set((X509Certificate) ctx.sslSession().getLocalCertificates()[0]);
                  return HttpResponse.of("OK");
              });
        });

        // Create a new client with a new ClientFactory to establish a new connection with the new certificate.
        final BlockingWebClient client1 =
                WebClient.builder(server.httpsUri())
                         .factory(ClientFactory.builder()
                                               .tlsCustomizer(sslContextBuilder -> {
                                                   sslContextBuilder.trustManager(newCert.certificate());
                                               }).build())
                         .build()
                         .blocking();
        final AggregatedHttpResponse response2 = client1.get("/");
        assertThat(response2.status()).isEqualTo(HttpStatus.OK);
        assertThat(sslContextRef.get().getNotBefore()).isEqualTo(newCert.cert().getNotBefore());

        client0.options().factory().closeAsync();
        client1.options().factory().closeAsync();

        assertThatThrownBy(() -> {
            server.server().reconfigure(sb -> {
                sb.service("/", (ctx, req) -> HttpResponse.of("OK"));
            });
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessage("TLS not configured; cannot serve HTTPS");
    }
}
