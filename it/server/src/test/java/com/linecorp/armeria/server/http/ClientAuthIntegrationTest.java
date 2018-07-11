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

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.client.logging.LoggingClientBuilder;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingServiceBuilder;
import com.linecorp.armeria.testing.server.SelfSignedCertificateRule;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

public class ClientAuthIntegrationTest {

    @ClassRule
    public static SelfSignedCertificateRule serverCert = new SelfSignedCertificateRule();

    @ClassRule
    public static SelfSignedCertificateRule clientCert = new SelfSignedCertificateRule();

    @ClassRule
    public static ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final SslContext sslContext =
                    SslContextBuilder.forServer(serverCert.certificateFile(), serverCert.privateKeyFile())
                                     .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                     .clientAuth(ClientAuth.REQUIRE)
                                     .build();
            sb.tls(sslContext)
              .service("/", (ctx, req) -> HttpResponse.of("success"))
              .decorator(new LoggingServiceBuilder().newDecorator());
        }
    };

    @Test
    public void normal() {
        final HttpClient client = new HttpClientBuilder(rule.httpsUri("/"))
                .factory(new ClientFactoryBuilder()
                                 .sslContextCustomizer(ctx -> ctx
                                         .keyManager(clientCert.certificateFile(), clientCert.privateKeyFile())
                                         .trustManager(InsecureTrustManagerFactory.INSTANCE))
                                 .build())
                .decorator(new LoggingClientBuilder().newDecorator())
                .build();
        assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
    }
}
