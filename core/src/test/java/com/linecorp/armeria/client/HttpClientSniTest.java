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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.cert.X509Certificate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.logging.LoggingClient;
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
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpClientSniTest {

    private static int httpsPort;
    private static ClientFactory clientFactory;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.virtualHost("a.com")
              .service("/", new SniTestService("a.com"))
              .tlsSelfSigned()
              .and()
              .defaultVirtualHost()
              .defaultHostname("b.com")
              .service("/", new SniTestService("b.com"))
              .tlsSelfSigned();
        }
    };

    @BeforeAll
    static void init() {
        httpsPort = server.httpsPort();
        clientFactory = ClientFactory.builder()
                                     .tlsNoVerify()
                                     .addressResolverGroupFactory(group -> MockAddressResolverGroup.localhost())
                                     .build();
    }

    @AfterAll
    static void destroy() {
        clientFactory.close();
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
    void disallowCustomAuthorityWithAdditionalHeadersInBaseURI() throws Exception {
        final WebClient client = WebClient.builder("https://127.0.0.1:" + httpsPort)
                                          .factory(clientFactory)
                                          .build();
        try (SafeCloseable unused = Clients.withHeader(HttpHeaderNames.AUTHORITY, "a.com:" + httpsPort)) {
            final AggregatedHttpResponse response = client.get("/").aggregate().get();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("b.com: CN=b.com");
        }
    }

    @Test
    void allowCustomAuthorityWithAdditionalHeadersInNonBaseURI() throws Exception {
        final WebClient client = WebClient.builder()
                                          .factory(clientFactory)
                                          .decorator(LoggingClient.newDecorator())
                                          .build();
        try (SafeCloseable unused = Clients.withHeader(HttpHeaderNames.AUTHORITY, "a.com:" + httpsPort)) {
            final AggregatedHttpResponse response =
                    client.get("https://127.0.0.1:" + httpsPort).aggregate().get();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("a.com: CN=a.com");
        }
    }

    @Test
    void testTlsNoVerifyHosts() throws Exception {
        try (ClientFactory clientFactoryIgnoreHosts = ClientFactory.builder()
                                                                   .tlsNoVerifyHosts("a.com")
                                                                   .tlsNoVerifyHosts("b.com")
                                                                   .addressResolverGroupFactory(
                                                                           group -> MockAddressResolverGroup
                                                                                   .localhost())
                                                                   .build()) {
            assertThat(get("a.com", clientFactoryIgnoreHosts)).isEqualTo("a.com: CN=a.com");
            assertThat(get("b.com", clientFactoryIgnoreHosts)).isEqualTo("b.com: CN=b.com");
            assertThatThrownBy(() -> get("d.com", clientFactoryIgnoreHosts))
                    .hasStackTraceContaining("javax.net.ssl.SSLHandshakeException");
        }
    }

    private static String get(String fqdn) throws Exception {
        return get(fqdn, clientFactory);
    }

    private static String get(String fqdn, ClientFactory clientFactory) throws Exception {
        final WebClient client = WebClient.builder("https://" + fqdn + ':' + httpsPort)
                                          .factory(clientFactory)
                                          .build();

        final AggregatedHttpResponse response = client.get("/").aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        return response.contentUtf8();
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
