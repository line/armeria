/*
 * Copyright 2021 LINE Corporation
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

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AdditionalAuthorityTest {

    @RegisterExtension
    static ServerExtension serverA = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.decorator(LoggingService.newDecorator());
            sb.virtualHost("foo")
              .service("/", (ctx, req) -> HttpResponse.of("foo/" + req.authority()));

            sb.virtualHost("bar")
              .service("/", (ctx, req) -> HttpResponse.of("bar/" + req.authority()));

            sb.virtualHost("baz")
              .service("/", (ctx, req) -> HttpResponse.of("baz/" + req.authority()));
        }
    };

    @RegisterExtension
    static ServerExtension serverB = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("serverB/" + req.authority()));
        }
    };

    private static BlockingWebClient client;
    private static ClientFactory clientFactory;
    private static int serverAPort;

    @BeforeAll
    static void beforeAll() {
        clientFactory =
                ClientFactory.builder()
                             .addressResolverGroupFactory(
                                     eventLoop -> MockAddressResolverGroup.localhost())
                             .build();

        client = WebClient.builder()
                          .factory(clientFactory)
                          .build()
                          .blocking();
        serverAPort = serverA.httpPort();
    }

    @AfterAll
    static void afterAll() {
        clientFactory.closeAsync();
    }

    @Test
    void shouldRespectAuthorityInAdditionalHeaders() {
        try (SafeCloseable ignored = Clients.withContextCustomizer(
                ctx -> ctx.addAdditionalRequestHeader(HttpHeaderNames.AUTHORITY,
                                                      "bar:" + serverAPort))) {

            assertThat(client.get("http://foo:" + serverAPort).contentUtf8())
                    .isEqualTo("bar/bar:" + serverAPort);
        }
    }

    @Test
    void shouldRespectAuthorityInClient() {
        final BlockingWebClient client = WebClient.builder()
                                                  .factory(clientFactory)
                                                  .authority("foo")
                                                  .build()
                                                  .blocking();

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            // A request is sent to 'bar' but 'foo' is used for :authority.
            assertThat(client.get("http://bar:" + serverAPort).contentUtf8())
                    .isEqualTo("foo/foo");
            final Endpoint endpoint = captor.get().endpoint();
            assertThat(endpoint.authority()).isEqualTo("bar:" + serverAPort);
        }
    }

    @Test
    void shouldRespectAuthorityInRequestOptions() {
        final BlockingWebClient client = WebClient.builder()
                                                  .factory(clientFactory)
                                                  .authority("foo")
                                                  .build()
                                                  .blocking();

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final String content = client.prepare()
                                         .get("http://bar:" + serverAPort)
                                         .authority("baz")
                                         .execute()
                                         .contentUtf8();
            // Make sure that the authority in the request options overrides the authority specified in the
            // client builder.
            assertThat(content).isEqualTo("baz/baz");

            final Endpoint endpoint = captor.get().endpoint();
            assertThat(endpoint.authority()).isEqualTo("bar:" + serverAPort);
        }
    }

    @Test
    void shouldUseAuthorityAsEndpointWithNonBaseUriClient() {
        final HttpRequest request = HttpRequest.of(RequestHeaders.builder(HttpMethod.GET, "/")
                                                                 .scheme("http")
                                                                 .authority("bar:" + serverAPort)
                                                                 .build());
        assertThat(client.execute(request).contentUtf8())
                .isEqualTo("bar/bar:" + serverAPort);
    }

    @Test
    void shouldIgnoreAuthorityInRequestWithBaseUriClient() {
        final HttpRequest request = HttpRequest.of(RequestHeaders.builder(HttpMethod.GET, "/")
                                                                 .scheme("http")
                                                                 .authority("bar:" + serverAPort)
                                                                 .build());
        final BlockingWebClient baseClient = WebClient.builder("http://foo:" + serverAPort)
                                                      .factory(clientFactory)
                                                      .build()
                                                      .blocking();

        // Ignore the authority in the RequestHeaders and use the base URI as an authority header.
        // Neither the Endpoint nor the authority has changed.
        assertThat(baseClient.execute(request).contentUtf8())
                .isEqualTo("foo/foo:" + serverAPort);
    }

    @Test
    void shouldNotUseAuthorityAsEndpointWithBaseUriWebClient() {
        final int serverBPort = serverB.httpPort();
        final WebClient clientA = WebClient.builder("http://foo:" + serverBPort)
                                           .factory(client.options().factory())
                                           .build();

        try (SafeCloseable ignored = Clients.withContextCustomizer(
                ctx -> ctx.addAdditionalRequestHeader(HttpHeaderNames.AUTHORITY,
                                                      "bar:" + serverAPort))) {

            final HttpRequest request2 = HttpRequest.of(HttpMethod.GET, "/");
            // The default authority (`foo`) got overridden to `bar` but the endpoint (serverB) has not changed.
            assertThat(clientA.execute(request2).aggregate().join().contentUtf8())
                    .isEqualTo("serverB/bar:" + serverAPort);
        }
    }

    @Test
    void noAuthority() {
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/"));
        assertThatThrownBy(() -> client.execute(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scheme and authority must be specified in");
    }

    @Test
    void absolutePath() {
        final HttpRequest request =
                HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "http://foo:" + serverAPort));
        assertThat(client.execute(request).contentUtf8()).isEqualTo(
                "foo/foo:" + serverAPort);
    }

    @CsvSource({ "[::1, Invalid bracketed host/port",
                 ":8080, Not a valid domain name",
                 "foo:, Missing port number" })
    @ParameterizedTest
    void validateAuthority(String invalidAuthority, String message) {
        assertThatThrownBy(() -> WebClient.builder().authority(invalidAuthority))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);

        assertThatThrownBy(() -> WebClient.of().prepare().authority(invalidAuthority))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }
}
