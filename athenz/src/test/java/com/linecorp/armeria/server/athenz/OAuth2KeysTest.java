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

package com.linecorp.armeria.server.athenz;

import static com.linecorp.armeria.server.athenz.AthenzDocker.ADMIN_ROLE;
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_DOMAIN_NAME;
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_SERVICE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.athenz.AthenzClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerTlsConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.ssl.ClientAuth;

class OAuth2KeysTest {

    private static final BlockingQueue<ClientRequestContext> athenzCtxs = new LinkedBlockingQueue<>();

    @Order(0)
    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension();

    @Order(0)
    @RegisterExtension
    static final SelfSignedCertificateExtension clientCert = new SelfSignedCertificateExtension();

    @Order(1)
    @RegisterExtension
    static final AthenzExtension athenzExtension = new AthenzExtension();

    @Order(2)
    @RegisterExtension
    static ServerExtension athenzProxyServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.https(8083);
            final TlsProvider tlsProvider =
                    TlsProvider.builder()
                               .keyPair(serverCert.tlsKeyPair())
                               .trustedCertificates(clientCert.certificate())
                               .build();
            final ServerTlsConfig serverTlsConfig =
                    ServerTlsConfig.builder()
                                   .clientAuth(ClientAuth.REQUIRE)
                                   .build();
            sb.tlsProvider(tlsProvider, serverTlsConfig);
            final ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE);
            final WebClient webClient = ztsBaseClient.webClient(cb -> {
                cb.decorator((delegate, ctx, req) -> {
                    athenzCtxs.add(ctx);
                    return delegate.execute(ctx, req);
                });
                cb.decorator(LoggingClient.newDecorator());
            });
            sb.service(Route.ofCatchAll(), (ctx, req) -> {
                final HttpRequest newReq = req.mapHeaders(header -> {
                    final String path = header.path();
                    final String normalizedPath = path.substring("/zts/v1".length());
                    return header.toBuilder()
                                 .path(normalizedPath)
                                 .build();
                });
                return webClient.execute(newReq);
            });
        }
    };

    @Order(3)
    @RegisterExtension
    static ServerExtension rfcServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws InterruptedException {
            final ZtsBaseClient ztsBaseClient =
                    ZtsBaseClient.builder(athenzProxyServer.httpsUri())
                                 .keyPair(clientCert::tlsKeyPair)
                                 .trustedCertificate(serverCert.certificateFile())
                                 .build();
            sb.decorator("/admin", AthenzService.builder(ztsBaseClient)
                                                .action("obtain")
                                                .resource("secrets")
                                                .policyConfig(new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                                .oauth2KeysPath("/oauth2/keys?rfc=true") // RFC path
                                                .newDecorator());
            sb.service("/admin", (ctx, req) -> {
                return HttpResponse.of("hello");
            });
        }
    };

    @Order(3)
    @RegisterExtension
    static ServerExtension nonRfcServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws InterruptedException {
            final ZtsBaseClient ztsBaseClient =
                    ZtsBaseClient.builder(athenzProxyServer.httpsUri())
                                 .keyPair(clientCert::tlsKeyPair)
                                 .trustedCertificate(serverCert.certificateFile())
                                 .build();
            sb.decorator("/admin", AthenzService.builder(ztsBaseClient)
                                                .action("obtain")
                                                .resource("secrets")
                                                .policyConfig(new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                                .oauth2KeysPath("/oauth2/keys") // Non-RFC path
                                                .newDecorator());
            sb.service("/admin", (ctx, req) -> {
                return HttpResponse.of("hello");
            });
        }
    };

    @Test
    void shouldUseNonRfcKeys() throws InterruptedException {
        final BlockingWebClient client =
                WebClient.builder(nonRfcServer.httpUri())
                         .decorator(AthenzClient.newDecorator(athenzExtension.newZtsBaseClient(TEST_SERVICE),
                                                              TEST_DOMAIN_NAME, ADMIN_ROLE,
                                                              TokenType.ACCESS_TOKEN))
                         .build()
                         .blocking();

        final AggregatedHttpResponse response = client.get("/admin");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("hello");
        final List<ServiceRequestContext> sctxs = athenzProxyServer.requestContextCaptor().all();
        boolean foundNonRfc = false;
        for (final ServiceRequestContext sctx : sctxs) {
            final String rawPath = sctx.rawPath();
            if ("/zts/v1/oauth2/keys".equals(rawPath)) {
                foundNonRfc = true;
            } else if ("/zts/v1/oauth2/key?rfc=true".equals(rawPath)) {
                foundNonRfc = false;
            }
        }
        assertThat(foundNonRfc).isTrue();
    }

    @Test
    void shouldUseRfcKeys() throws InterruptedException {
        final BlockingWebClient client =
                WebClient.builder(rfcServer.httpUri())
                         .decorator(AthenzClient.newDecorator(athenzExtension.newZtsBaseClient(TEST_SERVICE),
                                                              TEST_DOMAIN_NAME, ADMIN_ROLE,
                                                              TokenType.ACCESS_TOKEN))
                         .build()
                         .blocking();

        final AggregatedHttpResponse response = client.get("/admin");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("hello");
        final List<ServiceRequestContext> sctxs = athenzProxyServer.requestContextCaptor().all();
        boolean foundRfc = false;
        for (ServiceRequestContext sctx : sctxs) {
            final String rawPath = sctx.rawPath();
            if ("/zts/v1/oauth2/keys?rfc=true".equals(rawPath)) {
                foundRfc = true;
            } else if ("/zts/v1/oauth2/keys".equals(rawPath)) {
                foundRfc = false;
            }
        }
        assertThat(foundRfc).isTrue();
    }
}
