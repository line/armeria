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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class WebClientAdditionalAuthorityTest {

    @RegisterExtension
    static ServerExtension fooServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("foo.com/" + req.authority()));
        }
    };

    @RegisterExtension
    static SelfSignedCertificateExtension barCertificate = new SelfSignedCertificateExtension("bar.com");

    @RegisterExtension
    static ServerExtension barServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tls(barCertificate.certificateFile(), barCertificate.privateKeyFile());
            sb.service("/", (ctx, req) -> HttpResponse.of("bar.com/" + req.authority()));
        }
    };

    @RegisterExtension
    static ServerExtension bazServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("baz.com/" + req.authority()));
        }
    };

    private static BlockingWebClient client;
    private static ClientFactory clientFactory;

    @BeforeAll
    static void beforeAll() {
        clientFactory =
                ClientFactory.builder()
                             .addressResolverGroupFactory(
                                     eventLoop -> MockAddressResolverGroup.localhost())
                             .tlsCustomizer(customizer -> {
                                 customizer.trustManager(barCertificate.certificate());
                             })
                             .build();

        client = WebClient.builder()
                          .factory(clientFactory)
                          .build()
                          .blocking();
    }

    @AfterAll
    static void afterAll() {
        clientFactory.closeAsync();
    }

    @CsvSource({ "h1c, :authority", "h2c, :authority", "http, :authority",
                 "h1c, Host", "h2c, Host", "http, Host" })
    @ParameterizedTest
    void shouldRespectAuthorityInAdditionalHeaders(String protocol, String headerName) {
        try (SafeCloseable ignored = Clients.withContextCustomizer(
                ctx -> ctx.addAdditionalRequestHeader(headerName, "bar.com"));
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {

            assertThat(client.get(protocol + "://foo.com:" + fooServer.httpPort()).contentUtf8())
                    .isEqualTo("foo.com/bar.com");
            assertThat(captor.get().endpoint().authority())
                    .isEqualTo("foo.com:" + fooServer.httpPort());
            assertThat(captor.get().log().whenComplete().join().requestHeaders().authority())
                    .isEqualTo("bar.com");
        }
    }

    @CsvSource({ "h1c", "h2c", "http" })
    @ParameterizedTest
    void shouldRespectAuthorityOverHostHeader(String protocol) {
        try (SafeCloseable ignored = Clients.withContextCustomizer(
                ctx -> {
                    ctx.addAdditionalRequestHeader(":authority", "bar.com");
                    ctx.addAdditionalRequestHeader("Host", "ignored.com");
                }
        );
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {

            assertThat(client.get(protocol + "://foo.com:" + fooServer.httpPort()).contentUtf8())
                    .isEqualTo("foo.com/bar.com");
            assertThat(captor.get().endpoint().authority())
                    .isEqualTo("foo.com:" + fooServer.httpPort());
            assertThat(captor.get().log().whenComplete().join().requestHeaders().authority())
                    .isEqualTo("bar.com");
        }
    }

    @CsvSource({ "h1c, :authority", "h2c, :authority", "http, :authority",
                 "h1c, Host", "h2c, Host", "http, Host" })
    @ParameterizedTest
    void shouldRespectAuthorityInDefaultHeaders(String protocol, String headerName) {
        final BlockingWebClient client = WebClient.builder()
                                                  .factory(clientFactory)
                                                  .setHeader(headerName, "bar.com")
                                                  .build()
                                                  .blocking();

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.get(protocol + "://foo.com:" + fooServer.httpPort()).contentUtf8())
                    .isEqualTo("foo.com/bar.com");

            assertThat(captor.get().endpoint().authority())
                    .isEqualTo("foo.com:" + fooServer.httpPort());
            assertThat(captor.get().log().whenComplete().join().requestHeaders().authority())
                    .isEqualTo("bar.com");
        }
    }

    @CsvSource({ "h1c, :authority", "h2c, :authority", "http, :authority",
                 "h1c, Host", "h2c, Host", "http, Host" })
    @ParameterizedTest
    void shouldRespectAuthorityInRequestHeaders(String protocol, String headerName) {
        final BlockingWebClient client = WebClient.builder()
                                                  .factory(clientFactory)
                                                  .setHeader(headerName, "baz.com")
                                                  .build()
                                                  .blocking();

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final RequestHeaders headers =
                    RequestHeaders.builder(HttpMethod.GET, protocol + "://foo.com:" + fooServer.httpPort())
                                  .authority("bar.com")
                                  .build();

            assertThat(client.execute(HttpRequest.of(headers)).contentUtf8())
                    .isEqualTo("foo.com/bar.com");

            assertThat(captor.get().endpoint().authority())
                    .isEqualTo("foo.com:" + fooServer.httpPort());
            assertThat(captor.get().log().whenComplete().join().requestHeaders().authority())
                    .isEqualTo("bar.com");
        }
    }

    @CsvSource({ "H1C, :authority", "H2C, :authority", "HTTP, :authority",
                 "H1C, Host", "H2C, Host", "HTTP, Host" })
    @ParameterizedTest
    void shouldOverrideBaseAuthorityWithRequestAuthority(SessionProtocol protocol, String headerName) {
        final BlockingWebClient client =
                WebClient.builder()
                         .factory(clientFactory)
                         .addHeader(headerName, "foo.com:" + fooServer.httpPort())
                         .build()
                         .blocking();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final RequestHeaders headers =
                    RequestHeaders.builder(HttpMethod.GET, "/")
                                  .scheme(protocol)
                                  .authority("bar.com:" + barServer.httpPort())
                                  .build();

            assertThat(client.execute(HttpRequest.of(headers)).contentUtf8())
                    .isEqualTo("bar.com/bar.com:" + barServer.httpPort());
            assertThat(captor.get().endpoint().authority())
                    .isEqualTo("bar.com:" + barServer.httpPort());
            assertThat(captor.get().log().whenComplete().join().requestHeaders().authority())
                    .isEqualTo("bar.com:" + barServer.httpPort());
        }
    }

    @CsvSource({ "H1C, :authority", "H2C, :authority", "HTTP, :authority",
                 "H1C, Host", "H2C, Host", "HTTP, Host" })
    @ParameterizedTest
    void shouldOverrideRequestAuthorityWithAdditionalAuthority(String protocol, String headerName) {
        try (SafeCloseable ignored = Clients.withHeader(headerName, "baz.com");
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {

            final RequestHeaders headers =
                    RequestHeaders.builder(HttpMethod.GET, protocol + "://foo.com:" + fooServer.httpPort())
                                  .authority("bar.com")
                                  .build();

            assertThat(client.execute(HttpRequest.of(headers)).contentUtf8())
                    .isEqualTo("foo.com/baz.com");
            assertThat(captor.get().endpoint().authority())
                    .isEqualTo("foo.com:" + fooServer.httpPort());
            assertThat(captor.get().log().whenComplete().join().requestHeaders().authority())
                    .isEqualTo("baz.com");
        }
    }

    @ValueSource(strings = { "h1c", "h2c", "http" })
    @ParameterizedTest
    void shouldUseAuthorityAsEndpointWithNonBaseUriClient(String protocol) {
        final HttpRequest request = HttpRequest.of(RequestHeaders.builder(HttpMethod.GET, "/")
                                                                 .scheme(protocol)
                                                                 .authority("bar.com:" + barServer.httpPort())
                                                                 .build());
        assertThat(client.execute(request).contentUtf8())
                .isEqualTo("bar.com/bar.com:" + barServer.httpPort());
    }

    @ValueSource(strings = { "h1", "h2", "https" })
    @ParameterizedTest
    void shouldUseAuthorityForSni(String protocol) {
        final HttpRequest request = HttpRequest.of(RequestHeaders.builder(HttpMethod.GET, "/")
                                                                 .authority("bar.com")
                                                                 .build());
        final BlockingWebClient client =
                WebClient.builder(protocol, Endpoint.of("127.0.0.1", barServer.httpsPort()))
                         .factory(clientFactory)
                         .decorator(LoggingClient.newDecorator())
                         .build()
                         .blocking();
        assertThat(client.execute(request).contentUtf8())
                .isEqualTo("bar.com/bar.com");
    }

    @CsvSource({ "h1c, :authority", "h2c, :authority", "http, :authority",
                 "h1c, Host", "h2c, Host", "http, Host" })
    @ParameterizedTest
    void shouldNotUseAuthorityAsEndpointWithBaseUriWebClient(String protocol, String headerName) {
        final WebClient clientA = WebClient.builder(protocol + "://foo.com:" + fooServer.httpPort())
                                           .factory(client.options().factory())
                                           .build();

        try (SafeCloseable ignored = Clients.withContextCustomizer(
                ctx -> ctx.addAdditionalRequestHeader(headerName,
                                                      "bar.com:" + barServer.httpPort()));
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {

            final HttpRequest request2 = HttpRequest.of(HttpMethod.GET, "/");
            // The default authority (`foo`) got overridden to `bar` but the endpoint (fooServer) has not
            // changed.
            assertThat(clientA.execute(request2).aggregate().join().contentUtf8())
                    .isEqualTo("foo.com/bar.com:" + barServer.httpPort());

            assertThat(captor.get().endpoint().authority())
                    .isEqualTo("foo.com:" + fooServer.httpPort());
            assertThat(captor.get().log().whenComplete().join().requestHeaders().authority())
                    .isEqualTo("bar.com:" + barServer.httpPort());
        }
    }

    @Test
    void noAuthority() {
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/"));
        assertThatThrownBy(() -> client.execute(request))
                .isInstanceOf(UnprocessedRequestException.class)
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ctx.sessionProtocol() cannot be 'undefined'");
    }

    @Test
    void absolutePath() {
        final HttpRequest request =
                HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "http://foo.com:" + fooServer.httpPort()));
        assertThat(client.execute(request).contentUtf8()).isEqualTo(
                "foo.com/foo.com:" + fooServer.httpPort());
    }
}
