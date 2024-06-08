/*
 * Copyright 2022 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

class ServerTlsHandshakeMetricsTest {

    private static final MeterRegistry meterRegistry = PrometheusMeterRegistries.newRegistry();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.https(0);
            sb.tlsSelfSigned();
            sb.meterRegistry(meterRegistry);
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @BeforeEach
    void clearMetrics() {
        meterRegistry.clear();
    }

    @ParameterizedTest
    @CsvSource({
            "H1, TLSv1.2, h1",
            "H1, TLSv1.3, h1",
            "H2, TLSv1.2, h2",
            "H2, TLSv1.3, h2"
    })
    void handshakeSuccess(SessionProtocol sessionProtocol, String tlsProtocol, String expectedProtocol) {
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .tlsNoVerify()
                                  .tlsCustomizer(sslCtxBuilder -> sslCtxBuilder.protocols(tlsProtocol))
                                  .build()) {
            final BlockingWebClient client =
                    WebClient.builder(server.uri(sessionProtocol))
                             .factory(clientFactory)
                             .build()
                             .blocking();

            client.get("/");

            // Should record only one handshake.
            await().untilAsserted(() -> {
                assertThat(meterRegistry.find("armeria.server.tls.handshakes")
                                        .counters()).hasSize(1);
            });

            // ... and it should be successful.
            assertThat(meterRegistry.find("armeria.server.tls.handshakes")
                                    .tag("result", "success")
                                    .counters()).hasSize(1);

            // ... and it should be 1 with the correct tags.
            final Counter counter =
                    meterRegistry.find("armeria.server.tls.handshakes")
                                 .tag("cipher.suite", value -> value.startsWith("TLS_"))
                                 .tag("common.name", server.server().defaultHostname())
                                 .tag("protocol", expectedProtocol)
                                 .tag("result", "success")
                                 .tag("tls.protocol", tlsProtocol)
                                 .counter();
            assertThat(counter)
                    .withFailMessage("Failed to find the matching TLS handshake counter: %s",
                                     MoreMeters.measureAll(meterRegistry))
                    .isNotNull();
            assertThat(counter.count()).isOne();
        }
    }

    @Test
    void handshakeFailure() {
        // Make a request that will fail due to a certificate trust issue.
        // Note that our server uses a self-signed certificate and the client will not trust it.
        final BlockingWebClient client = WebClient.of(server.httpsUri()).blocking();
        assertThatThrownBy(() -> client.get("/"))
                .isInstanceOf(UnprocessedRequestException.class)
                .hasCauseInstanceOf(SSLHandshakeException.class);

        // Should record only one handshake.
        await().untilAsserted(() -> {
            assertThat(meterRegistry.find("armeria.server.tls.handshakes")
                                    .counters()).hasSize(1);
        });

        // ... and it should be failed.
        assertThat(meterRegistry.find("armeria.server.tls.handshakes")
                                .tag("result", "failure")
                                .counters()).hasSize(1);

        // ... and it should be 1 with the expected tags.
        final Counter counter =
                meterRegistry.find("armeria.server.tls.handshakes")
                             .tag("cipher.suite", "")
                             .tag("common.name", server.server().defaultHostname())
                             .tag("protocol", "")
                             .tag("result", "failure")
                             .tag("tls.protocol", "")
                             .counter();
        assertThat(counter)
                .withFailMessage("Failed to find the matching TLS handshake counter: %s",
                                 MoreMeters.measureAll(meterRegistry))
                .isNotNull();
        assertThat(counter.count()).isOne();
    }
}
