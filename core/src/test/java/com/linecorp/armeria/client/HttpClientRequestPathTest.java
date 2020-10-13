/*
 * Copyright 2019 LINE Corporation
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

import static com.linecorp.armeria.common.HttpHeaderNames.LOCATION;
import static com.linecorp.armeria.common.HttpStatus.INTERNAL_SERVER_ERROR;
import static com.linecorp.armeria.common.HttpStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpClientRequestPathTest {

    private static long counter;

    @RegisterExtension
    @Order(10)
    static final ServerExtension server1 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(OK));
            sb.service("/new-location", (ctx, req) -> HttpResponse.of(OK));
        }
    };

    @RegisterExtension
    @Order(20)
    static final ServerExtension server2 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0)
              .https(0)
              .tlsSelfSigned()
              .service("/simple-client", (ctx, req) -> HttpResponse.of(OK))
              .service("/retry", (ctx, req) -> {
                  if (++counter < 3) {
                      return HttpResponse.of(INTERNAL_SERVER_ERROR);
                  } else {
                      return HttpResponse.of(OK);
                  }
              })
              .service("/redirect", (ctx, req) -> {
                  final HttpHeaders headers = ResponseHeaders.of(HttpStatus.TEMPORARY_REDIRECT,
                                                                 LOCATION, server1.httpUri() + "/new-location");
                  return HttpResponse.of(headers);
              });
        }
    };

    @BeforeEach
    void setUp() {
        counter = 0;
    }

    @ParameterizedTest
    @EnumSource(value = HttpMethod.class, mode = Mode.EXCLUDE, names = "UNKNOWN")
    void default_withAbsolutePath(HttpMethod method) {
        final HttpRequest request = HttpRequest.of(method, server2.httpUri() + "/simple-client");
        final HttpResponse response = WebClient.of().execute(request);
        assertThat(response.aggregate().join().status()).isEqualTo(OK);
    }

    @Test
    void default_withInvalidScheme() {
        final WebClient client = WebClient.of();
        final HttpResponse response = client.get("unknown://example.com/path");
        response.aggregate().whenComplete((agg, cause) -> {
            assertThat(cause).isInstanceOf(IllegalArgumentException.class)
                             .hasMessageContaining("Failed to parse scheme: unknown");
        });
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, mode = Mode.EXCLUDE, names = "PROXY")
    void default_withScheme(SessionProtocol protocol) {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, server2.uri(protocol) + "/simple-client");
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final WebClient client = WebClient.builder().factory(ClientFactory.insecure()).build();
            final HttpResponse response = client.execute(request);
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.sessionProtocol()).isEqualTo(protocol);
            assertThat(response.aggregate().join().status()).isEqualTo(OK);
        }
    }

    @Test
    void default_withRelativePath() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/simple-client");
        final HttpResponse response = WebClient.of().execute(request);
        assertThatThrownBy(() -> response.aggregate().join())
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scheme and authority must be specified");
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, mode = Mode.EXCLUDE, names = { "HTTP", "HTTPS", "PROXY"})
    void default_withRetryClient(SessionProtocol protocol) {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, server2.uri(protocol) + "/retry");
        final WebClient client = WebClient.builder()
                                          .decorator(RetryingClient.newDecorator(RetryRule.failsafe()))
                                          .factory(ClientFactory.insecure())
                                          .build();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.execute(request).aggregate().join();
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.sessionProtocol()).isEqualTo(protocol);
            for (RequestLogAccess child : ctx.log().partial().children()) {
                assertThat(child.partial().sessionProtocol()).isEqualTo(protocol);
            }
        }
    }

    @Test
    void custom_withAbsolutePath() {
        final WebClient client = WebClient.of(server1.httpUri());
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, server2.httpUri() + "/simple-client");
        assertThatThrownBy(() -> client.execute(request).aggregate().join()).hasCauseInstanceOf(
                IllegalArgumentException.class);
    }

    @Test
    void custom_withRelativePath() {
        final WebClient client = WebClient.of(server2.httpUri());
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/simple-client");
        final HttpResponse response = client.execute(request);
        assertThat(response.aggregate().join().status()).isEqualTo(OK);
    }

    @Test
    void redirect() {
        final WebClient client = WebClient.of(server2.httpUri());
        final AggregatedHttpResponse redirected = client.get("/redirect").aggregate().join();
        final String location = redirected.headers().get(LOCATION);
        assertThat(location).isNotNull();
        // Should use the default WebClient or a different WebClient to send a request to another endpoint.
        final AggregatedHttpResponse actual = WebClient.of().get(location).aggregate().join();
        assertThat(actual.status()).isEqualTo(OK);
    }

    @Test
    void absoluteUriWithoutPath() {
        final WebClient client = WebClient.of();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final HttpResponse httpResponse = client.get("http://127.0.0.1:" + server1.httpPort());
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.path()).isEqualTo("/");
            assertThat(httpResponse.aggregate().join().status()).isEqualTo(OK);
        }
    }
}
