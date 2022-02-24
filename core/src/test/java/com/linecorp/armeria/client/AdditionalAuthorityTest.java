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

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AdditionalAuthorityTest {

    @RegisterExtension
    static ServerExtension serverA = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.virtualHost("foo")
              .service("/", (ctx, req) -> HttpResponse.of("foo/" + req.authority()));

            sb.virtualHost("bar")
              .service("/", (ctx, req) -> HttpResponse.of("bar/" + req.authority()));
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
    private static int serverAPort;

    @BeforeAll
    static void beforeAll() {
        final ClientFactory clientFactory =
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
        client.options().factory().closeAsync();
    }

    @Test
    void additionalAuthorityHasHighestPrecedence() {
        try (SafeCloseable ignored = Clients.withContextCustomizer(
                ctx -> ctx.addAdditionalRequestHeader(HttpHeaderNames.AUTHORITY,
                                                      "bar:" + serverAPort))) {

            assertThat(client.get("http://foo:" + serverAPort).contentUtf8())
                    .isEqualTo("bar/bar:" + serverAPort);
        }
    }

    @Test
    void requestHeader() {
        final HttpRequest request = HttpRequest.of(RequestHeaders.builder(HttpMethod.GET, "/")
                                                                 .scheme("http")
                                                                 .authority("bar:" + serverAPort)
                                                                 .build());
        assertThat(client.execute(request).contentUtf8())
                .isEqualTo("bar/bar:" + serverAPort);
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

    @Test
    void shouldIgnoreInvalidAdditionalAuthority() {
        // Missing a closing bracket
        try (SafeCloseable ignored = Clients.withContextCustomizer(
                ctx -> ctx.addAdditionalRequestHeader(HttpHeaderNames.AUTHORITY, "[::1"))) {

            assertThatThrownBy(() -> client.get("http://foo:" + serverAPort))
                    .isInstanceOf(UnprocessedRequestException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid bracketed host/port");
        }

        // Port only
        try (SafeCloseable ignored = Clients.withContextCustomizer(
                ctx -> ctx.addAdditionalRequestHeader(HttpHeaderNames.AUTHORITY, ":8080"))) {

            final HttpRequest request = HttpRequest.of(RequestHeaders.builder(HttpMethod.GET, "/")
                                                                     .scheme("http")
                                                                     .authority("bar:" + serverAPort)
                                                                     .build());

            assertThatThrownBy(() -> client.execute(request))
                    .isInstanceOf(UnprocessedRequestException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Not a valid domain name");
        }

        // Missing a port number
        try (SafeCloseable ignored = Clients.withContextCustomizer(
                ctx -> ctx.addAdditionalRequestHeader(HttpHeaderNames.AUTHORITY, "foo:"))) {

            final HttpRequest request = HttpRequest.of(RequestHeaders.builder(HttpMethod.GET, "/")
                                                                     .scheme("http")
                                                                     .authority("bar:" + serverAPort)
                                                                     .build());

            assertThatThrownBy(() -> client.execute(request))
                    .isInstanceOf(UnprocessedRequestException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing port number");
        }
    }

    @Test
    void shouldNotUseAuthorityAsEndpointWithBaseUriWebClient() {
        final int serverBPort = serverB.httpPort();
        final WebClient clientA = WebClient.builder("http://foo:" + serverBPort)
                                           .factory(client.options().factory())
                                           .build();

        final HttpRequest request = HttpRequest.of(RequestHeaders.builder(HttpMethod.GET, "/")
                                                                 .scheme("http")
                                                                 .authority("bar:" + serverAPort)
                                                                 .build());
        assertThat(clientA.execute(request).aggregate().join().contentUtf8())
                // Ignore the authority in the RequestHeaders and use the base URI as an authority header.
                .isEqualTo("serverB/foo:" + serverBPort);

        try (SafeCloseable ignored = Clients.withContextCustomizer(
                ctx -> ctx.addAdditionalRequestHeader(HttpHeaderNames.AUTHORITY,
                                                      "bar:" + serverAPort))) {

            final HttpRequest request2 = HttpRequest.of(HttpMethod.GET, "/");
            assertThat(clientA.execute(request2).aggregate().join().contentUtf8())
                    // Ignore the authority in the additional headers and
                    // use the base URI as an authority header.
                    .isEqualTo("serverB/foo:" + serverBPort);
        }
    }
}
