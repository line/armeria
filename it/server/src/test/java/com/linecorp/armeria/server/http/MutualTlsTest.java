/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.server.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

class MutualTlsTest {

    @RegisterExtension
    static SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension();

    @RegisterExtension
    static SelfSignedCertificateExtension clientCert = new SelfSignedCertificateExtension();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.tls(serverCert.certificateFile(), serverCert.privateKeyFile());
            sb.tlsCustomizer(sslCtxBuilder -> {
                sslCtxBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE)
                             .clientAuth(ClientAuth.REQUIRE);
            });

            sb.service("/", (ctx, req) -> HttpResponse.of("success"));
            sb.decorator(LoggingService.builder().newDecorator());
        }
    };

    @Test
    void normal() {
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .tls(clientCert.certificateFile(), clientCert.privateKeyFile())
                                  .tlsNoVerify()
                                  .build()) {
            final BlockingWebClient client = WebClient.builder(server.httpsUri())
                                                      .factory(clientFactory)
                                                      .decorator(LoggingClient.newDecorator())
                                                      .build()
                                                      .blocking();
            assertThat(client.get("/").status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void shouldAllowOverridingTlsKey() {
        assertThatCode(() -> {
            ClientFactory.builder()
                         .tls(clientCert.certificateFile(), clientCert.privateKeyFile())
                         .tls(clientCert.privateKey(), clientCert.certificate());
        }).doesNotThrowAnyException();
    }
}
