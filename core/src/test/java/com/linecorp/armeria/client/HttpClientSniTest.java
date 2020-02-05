/*
 * Copyright 2016 LINE Corporation
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

import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.handler.ssl.util.SelfSignedCertificate;

class HttpClientSniTest {

    private static final Server server;

    private static int httpsPort;
    private static ClientFactory clientFactory;
    private static final SelfSignedCertificate sscA;
    private static final SelfSignedCertificate sscB;

    static {
        try {
            final ServerBuilder sb = Server.builder();
            sscA = new SelfSignedCertificate("a.com");
            sscB = new SelfSignedCertificate("b.com");

            sb.virtualHost("a.com")
              .service("/", new SniTestService("a.com"))
              .tls(sscA.certificate(), sscA.privateKey())
              .and()
              .defaultVirtualHost()
              .defaultHostname("b.com")
              .service("/", new SniTestService("b.com"))
              .tls(sscB.certificate(), sscB.privateKey());

            server = sb.build();
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    @BeforeAll
    static void init() throws Exception {
        server.start().get();
        httpsPort = server.activePorts().values().stream()
                          .filter(ServerPort::hasHttps).findAny().get().localAddress()
                          .getPort();
        clientFactory =
                ClientFactory.builder()
                             .tlsNoVerify()
                             .addressResolverGroupFactory(group -> MockAddressResolverGroup.localhost())
                             .build();
    }

    @AfterAll
    static void destroy() throws Exception {
        CompletableFuture.runAsync(() -> {
            clientFactory.close();
            server.stop();
            sscA.delete();
            sscB.delete();
        });
    }

    @Test
    void testMatch() throws Exception {
        testMatch("a.com");
        testMatch("b.com");
    }

    private static void testMatch(String fqdn) throws Exception {
        assertThat(get(fqdn)).isEqualTo(fqdn + ": CN=" + fqdn);
    }

    @Test
    void testMismatch() throws Exception {
        testMismatch("127.0.0.1");
        testMismatch("mismatch.com");
    }

    private static void testMismatch(String fqdn) throws Exception {
        assertThat(get(fqdn)).isEqualTo("b.com: CN=b.com");
    }

    private static String get(String fqdn) throws Exception {
        final WebClient client = WebClient.builder("https://" + fqdn + ':' + httpsPort)
                                          .factory(clientFactory)
                                          .build();

        final AggregatedHttpResponse response = client.get("/").aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        return response.contentUtf8();
    }

    @Test
    void testCustomAuthority() throws Exception {
        final WebClient client = WebClient.builder(SessionProtocol.HTTPS,
                                                   Endpoint.of("a.com", httpsPort)
                                                           .withIpAddr("127.0.0.1"))
                                          .factory(clientFactory)
                                          .build();

        final AggregatedHttpResponse response = client.get("/").aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("a.com: CN=a.com");
    }

    @Test
    void testCustomAuthorityWithAdditionalHeaders() throws Exception {
        final WebClient client = WebClient.builder("https://127.0.0.1:" + httpsPort)
                                          .factory(clientFactory)
                                          .build();
        try (SafeCloseable unused = Clients.withHttpHeader(HttpHeaderNames.AUTHORITY, "a.com:" + httpsPort)) {
            final AggregatedHttpResponse response = client.get("/").aggregate().get();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("a.com: CN=a.com");
        }
    }

    private static class SniTestService extends AbstractHttpService {

        private final String domainName;

        SniTestService(String domainName) {
            this.domainName = domainName;
        }

        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            final X509Certificate c = (X509Certificate) ctx.sslSession().getLocalCertificates()[0];
            final String name = c.getSubjectX500Principal().getName();
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, domainName + ": " + name);
        }
    }
}
