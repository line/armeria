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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.redirect.CyclicRedirectsException;
import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.client.redirect.TooManyRedirectsException;
import com.linecorp.armeria.client.redirect.UnexpectedDomainRedirectException;
import com.linecorp.armeria.client.redirect.UnexpectedProtocolRedirectException;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RedirectingClientTest {

    private static final AtomicInteger requestCounter = new AtomicInteger();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.tlsSelfSigned();
            sb.http(0);
            sb.http(0);
            sb.https(0);
            sb.service("/foo", (ctx, req) -> HttpResponse.ofRedirect("/fooRedirect1"))
              .service("/fooRedirect1", (ctx, req) -> HttpResponse.ofRedirect("/fooRedirect2"))
              .service("/fooRedirect2", (ctx, req) -> HttpResponse.of(200));

            sb.service("/protocolChange", (ctx, req) -> {
                if (requestCounter.getAndIncrement() > 0) {
                    assertThat(ctx.sessionProtocol()).isSameAs(SessionProtocol.H2);
                    return HttpResponse.of(200);
                } else {
                    return HttpResponse.ofRedirect(
                            "https://127.0.0.1:" + server.httpsPort() + "/protocolChange");
                }
            });

            sb.service("/portChange", (ctx, req) -> {
                if (requestCounter.getAndIncrement() > 0) {
                    return HttpResponse.of(200);
                } else {
                    return HttpResponse.ofRedirect(
                            "http://127.0.0.1:" + otherHttpPort(ctx) + "/portChange");
                }
            });

            sb.service("/seeOther", (ctx, req) -> HttpResponse.of(
                      req.aggregate().thenApply(aggregatedReq -> {
                          assertThat(aggregatedReq.contentUtf8()).isEqualTo("hello!");
                          return HttpResponse.ofRedirect(HttpStatus.SEE_OTHER, "/seeOtherRedirect");
                      })))
              .service("/seeOtherRedirect", (ctx, req) -> {
                  assertThat(ctx.method()).isSameAs(HttpMethod.GET);
                  return HttpResponse.of(200);
              });

            sb.service("/anotherDomain", (ctx, req) -> HttpResponse.ofRedirect(
                    "http://foo.com:" + server.httpPort() + "/anotherDomainRedirect"));
            sb.virtualHost("foo.com")
              .service("/anotherDomainRedirect", (ctx, req) -> {
                assertThat(req.authority()).isEqualTo("foo.com:" + server.server().activeLocalPort());
                return HttpResponse.of(200);
            });

            sb.service("/removeDotSegments/foo", (ctx, req) -> HttpResponse.ofRedirect("./bar"))
              .service("/removeDotSegments/bar", (ctx, req) -> HttpResponse.of(200))
              .service("/removeDoubleDotSegments/foo",
                       (ctx, req) -> HttpResponse.ofRedirect("../removeDotSegments/bar"));

            sb.service("/completeLoop1", (ctx, req) -> HttpResponse.ofRedirect("completeLoop2"))
              .service("/completeLoop2", (ctx, req) -> HttpResponse.ofRedirect("completeLoop3"))
              .service("/completeLoop3", (ctx, req) -> HttpResponse.ofRedirect("completeLoop1"));

            sb.service("/partialLoop1", (ctx, req) -> HttpResponse.ofRedirect("partialLoop2"))
              .service("/partialLoop2", (ctx, req) -> HttpResponse.ofRedirect("partialLoop3"))
              .service("/partialLoop3", (ctx, req) -> HttpResponse.ofRedirect("partialLoop2"));

            sb.service("/protocolLoop", (ctx, req) ->
                    HttpResponse.ofRedirect("h1c://127.0.0.1:" + server.httpPort() + "/protocolLoop"));

            sb.service("/authorityLoop", (ctx, req) -> HttpResponse.ofRedirect(
                    "http://domain1.com:" + server.httpPort() + "/authorityLoop"));
            sb.virtualHost("domain1.com")
              .service("/authorityLoop", (ctx, req) -> HttpResponse.ofRedirect(
                      "http://domain2.com:" + server.httpPort() + "/authorityLoop"));
            sb.virtualHost("domain2.com")
              .service("/authorityLoop", (ctx, req) -> HttpResponse.ofRedirect(
                      "http://domain1.com:" + server.httpPort() + "/authorityLoop"));

            sb.service("/queryLoop1", (ctx, req) -> {
                final String queryParams = ctx.query();
                final String redirectUrl = "/queryLoop2" + (queryParams == null ? "" : '?' + queryParams);
                return HttpResponse.ofRedirect(redirectUrl);
              })
              .service("/queryLoop2", (ctx, req) -> {
                  final String queryParams = ctx.query();
                  final String redirectUrl = "/queryLoop1?" + (queryParams == null ? "q=1" : queryParams);
                  return HttpResponse.ofRedirect(redirectUrl);
              });

            sb.service("/differentHttpMethod", (ctx, req) -> {
                if (ctx.method() == HttpMethod.GET) {
                    return HttpResponse.of(200);
                } else {
                    assertThat(ctx.method()).isSameAs(HttpMethod.POST);
                    return HttpResponse.ofRedirect(HttpStatus.SEE_OTHER, "/differentHttpMethod");
                }
            });

            sb.service("/unencodedLocation",
                       (ctx, req) -> HttpResponse.ofRedirect("/unencodedLocation/foo bar?value=${P}"));
            sb.service("/unencodedLocation/foo%20bar", (ctx, req) -> {
                if ("${P}".equals(ctx.queryParam("value"))) {
                    return HttpResponse.of(200);
                } else {
                    return HttpResponse.of(400);
                }
            });
        }

        private int otherHttpPort(ServiceRequestContext ctx) {
            final Optional<ServerPort> serverPort =
                    server.server().activePorts().values()
                          .stream().filter(ServerPort::hasHttp)
                          .filter(port -> port.localAddress().getPort() !=
                                          ctx.localAddress().getPort())
                          .findFirst();
            assertThat(serverPort).isPresent();
            return serverPort.get().localAddress().getPort();
        }
    };

    @BeforeEach
    void setUp() {
        requestCounter.set(0);
    }

    @Test
    void redirect_successWithRetry() {
        final AggregatedHttpResponse res = sendRequest(2);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
    }

    @Test
    void redirect_failExceedingTotalAttempts() {
        assertThatThrownBy(() -> sendRequest(1))
                .hasCauseInstanceOf(TooManyRedirectsException.class)
                .hasMessageContainingAll("maxRedirects: 1", "The original URI: ",
                                         "Redirect URIs:", "fooRedirect1", "fooRedirect2");
    }

    private static AggregatedHttpResponse sendRequest(int maxRedirects) {
        final WebClient client = WebClient.builder(server.httpUri())
                .decorator(LoggingClient.newDecorator())
                                          .followRedirects(RedirectConfig.builder()
                                                                         .maxRedirects(maxRedirects)
                                                                         .build())
                                          .build();
        return client.get("/foo").aggregate().join();
    }

    @Test
    void protocolChange() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(ClientFactory.insecure())
                                          .followRedirects(RedirectConfig.builder()
                                                                         .allowProtocols(SessionProtocol.HTTP)
                                                                         .build())
                                          .build();
        assertThatThrownBy(() -> client.get("/protocolChange").aggregate().join())
                .hasCauseInstanceOf(UnexpectedProtocolRedirectException.class)
                .hasMessageContaining("redirectProtocol: https (expected: [http])");

        requestCounter.set(0);
        final WebClient client1 = WebClient.builder(server.httpUri())
                                           .factory(ClientFactory.insecure())
                                           .decorator(LoggingClient.newDecorator())
                                           // Allows HTTPS by default when allowProtocols is not specified.
                                           .followRedirects()
                                           .build();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client1.get("/protocolChange").aggregate().join().status()).isSameAs(HttpStatus.OK);

            final ClientRequestContext ctx = captor.get();
            final List<SessionProtocol> protocols = ctx.log().whenComplete().join().children().stream()
                                                       .map(log -> log.ensureComplete().sessionProtocol())
                                                       .distinct()
                                                       .collect(toImmutableList());
            // H2C and H2, which are Actual protocols, are set
            assertThat(protocols).containsExactly(SessionProtocol.H2C, SessionProtocol.H2);
        }
    }

    @Test
    void portChange() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .followRedirects()
                                          .build();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.get("/portChange").aggregate().join().status()).isSameAs(HttpStatus.OK);

            final ClientRequestContext ctx = captor.get();
            final List<Integer> remotePorts =
                    ctx.log().whenComplete().join().children().stream()
                       .map(log -> ((InetSocketAddress) log.ensureComplete()
                                                           .channel().remoteAddress())
                               .getPort())
                       .distinct()
                       .collect(toImmutableList());
            assertThat(remotePorts).hasSize(2);
        }
    }

    @Test
    void seeOtherHttpMethodChangedToGet() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .followRedirects()
                                          .build();
        final AggregatedHttpResponse res = client.post("/seeOther", "hello!").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
    }

    @Test
    void webClientCreatedWithBaseUri_doesNotAllowRedirectionToOtherDomain() {
        try (ClientFactory factory = localhostAccessingClientFactory()) {
            final WebClient client = WebClient.builder(server.httpUri())
                                              .factory(factory)
                                              .followRedirects()
                                              .build();
            assertThatThrownBy(() -> client.get("/anotherDomain").aggregate().join())
                    .hasCauseInstanceOf(UnexpectedDomainRedirectException.class)
                    .hasMessageContaining("foo.com");

            final WebClient client1 = WebClient.builder(server.httpUri())
                                               .factory(factory)
                                               .followRedirects(
                                                       RedirectConfig.builder().allowDomains("foo.com").build())
                                               .build();
            assertThat(client1.get("/anotherDomain").aggregate().join().status()).isSameAs(HttpStatus.OK);
        }
    }

    @Test
    void webClientCreatedWithoutBaseUri_allowRedirectionToOtherDomain() {
        try (ClientFactory factory = localhostAccessingClientFactory()) {
            final WebClient client = WebClient.builder()
                                              .factory(factory)
                                              .followRedirects()
                                              .build();
            final AggregatedHttpResponse res = client.get(server.httpUri() + "/anotherDomain")
                                                     .aggregate()
                                                     .join();
            assertThat(res.status()).isSameAs(HttpStatus.OK);
        }
    }

    @Test
    void referenceResolution() {
        // /a/b with ./c is resolved to a/c
        // See https://datatracker.ietf.org/doc/html/rfc3986#section-5.2.4
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(ClientFactory.insecure())
                                          .followRedirects()
                                          .build();
        final AggregatedHttpResponse res1 = client.get("/removeDotSegments/foo").aggregate().join();
        assertThat(res1.status()).isSameAs(HttpStatus.OK);
        final AggregatedHttpResponse res2 = client.get("/removeDoubleDotSegments/foo").aggregate().join();
        assertThat(res2.status()).isSameAs(HttpStatus.OK);
    }

    @ParameterizedTest
    @MethodSource("provideRedirectPatterns")
    void cyclicRedirectsException(String originalPath, List<String> expectedPathRegexPatterns) {
        try (ClientFactory factory = localhostAccessingClientFactory()) {
            final RedirectConfig redirectConfig = RedirectConfig.builder()
                                                                .allowDomains("domain1.com", "domain2.com")
                                                                .build();
            final WebClient client = Clients.builder(server.httpUri())
                                            .factory(factory)
                                            .followRedirects(redirectConfig)
                                            .decorator(LoggingClient.newDecorator())
                                            .build(WebClient.class);

            assertThatThrownBy(() -> client.get(originalPath).aggregate().join())
                    .hasCauseInstanceOf(CyclicRedirectsException.class)
                    .hasMessageContainingAll("The original URI:", "Redirect URIs:")
                    .satisfies(exception -> {
                        final String message = exception.getMessage();
                        // All URIs have a port number.
                        expectedPathRegexPatterns.forEach(pattern ->
                              assertThat(message).containsPattern(pattern));
                    });
        }
    }

    private static Stream<Arguments> provideRedirectPatterns() {
        return Stream.of(
                Arguments.of("/completeLoop1",
                             redirectExceptionPathRegexPatterns(
                                     "http://.*:[0-9]+/completeLoop1",
                                     "http://.*:[0-9]+/completeLoop2",
                                     "http://.*:[0-9]+/completeLoop3")),
                Arguments.of("/partialLoop1",
                             redirectExceptionPathRegexPatterns(
                                     "http://.*:[0-9]+/partialLoop1",
                                     "http://.*:[0-9]+/partialLoop2",
                                     "http://.*:[0-9]+/partialLoop3")),
                Arguments.of("/protocolLoop",
                             redirectExceptionPathRegexPatterns(
                                     "http://.*:[0-9]+/protocolLoop")),
                Arguments.of("/authorityLoop",
                             redirectExceptionPathRegexPatterns(
                                     "http://.*:[0-9]+/authorityLoop",
                                     "http://domain1.com:[0-9]+/authorityLoop",
                                     "http://domain2.com:[0-9]+/authorityLoop")),
                Arguments.of("/queryLoop1",
                             redirectExceptionPathRegexPatterns(
                                     "http://.*:[0-9]+/queryLoop1",
                                     "http://.*:[0-9]+/queryLoop2",
                                     "http://.*:[0-9]+/queryLoop1\\?q=1",
                                     "http://.*:[0-9]+/queryLoop2\\?q=1")));
    }

    private static List<String> redirectExceptionPathRegexPatterns(String... patterns) {
        return ImmutableList.copyOf(patterns);
    }

    @Test
    void notCyclicRedirectsWhenHttpMethodDiffers() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .followRedirects()
                                          .build();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.post("/differentHttpMethod", HttpData.empty()).aggregate().join().status())
                    .isSameAs(HttpStatus.OK);
            assertThat(captor.get().log().whenComplete().join().children().size()).isEqualTo(2);
        }
    }

    @Test
    void unencodedLocation() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .followRedirects()
                                          .build();

        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.get("/unencodedLocation").aggregate().join().status()).isSameAs(HttpStatus.OK);
            ctx = captor.get();
        }

        final ImmutableList<RequestLog> logs = ctx.log().whenComplete().join()
                                                  .children().stream()
                                                  .map(log -> log.whenComplete().join())
                                                  .collect(toImmutableList());

        assertThat(logs.size()).isEqualTo(2);

        final ResponseHeaders log1headers = logs.get(0).responseHeaders();
        assertThat(log1headers.status()).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
        assertThat(log1headers.get(HttpHeaderNames.LOCATION))
                .isEqualTo("/unencodedLocation/foo bar?value=${P}");

        final RequestLog log2 = logs.get(1);
        assertThat(log2.requestHeaders().path()).isEqualTo("/unencodedLocation/foo%20bar?value=$%7BP%7D");
        assertThat(log2.responseHeaders().status()).isEqualTo(HttpStatus.OK);
        assertThat(log2.context().uri().toString()).endsWith("/unencodedLocation/foo%20bar?value=$%7BP%7D");
    }

    @Test
    void testResolveLocation() {
        // Absolute paths and URIs should supersede the original path.
        assertThat(resolveLocation("/a/", "/")).isEqualTo("h2c://foo/");
        assertThat(resolveLocation("/a/", "/b")).isEqualTo("h2c://foo/b");
        assertThat(resolveLocation("/a/", "//bar")).isEqualTo("h2c://bar/");
        assertThat(resolveLocation("/a/", "//bar/b")).isEqualTo("h2c://bar/b");
        assertThat(resolveLocation("/a/", "https://bar")).isEqualTo("https://bar/");
        assertThat(resolveLocation("/a/", "https://bar/b")).isEqualTo("https://bar/b");

        // Should reject the absolute URI with an unknown scheme.
        assertThat(resolveLocation("/a/", "a://bar")).isNull();

        // Should normalize the scheme into "http" or "https" in an absolute URI,
        // because we should not trust the response blindly, e.g. DDoS by enforcing HTTP/1.
        assertThat(resolveLocation("/a/", "h1c://bar")).isEqualTo("http://bar/");
        assertThat(resolveLocation("/a/", "h1://bar")).isEqualTo("https://bar/");

        // Simple cases
        assertThat(resolveLocation("/", "b")).isEqualTo("h2c://foo/b");
        assertThat(resolveLocation("/", "b/")).isEqualTo("h2c://foo/b/");
        assertThat(resolveLocation("/", "b/c")).isEqualTo("h2c://foo/b/c");
        assertThat(resolveLocation("/", "b/c/")).isEqualTo("h2c://foo/b/c/");

        assertThat(resolveLocation("/a", "b")).isEqualTo("h2c://foo/b");
        assertThat(resolveLocation("/a", "b/")).isEqualTo("h2c://foo/b/");
        assertThat(resolveLocation("/a", "b/c")).isEqualTo("h2c://foo/b/c");
        assertThat(resolveLocation("/a", "b/c/")).isEqualTo("h2c://foo/b/c/");

        assertThat(resolveLocation("/a/", "b")).isEqualTo("h2c://foo/a/b");
        assertThat(resolveLocation("/a/", "b/")).isEqualTo("h2c://foo/a/b/");
        assertThat(resolveLocation("/a/", "b/c")).isEqualTo("h2c://foo/a/b/c");
        assertThat(resolveLocation("/a/", "b/c/")).isEqualTo("h2c://foo/a/b/c/");

        // Single-dot cases
        assertThat(resolveLocation("/", ".")).isEqualTo("h2c://foo/");
        assertThat(resolveLocation("/", "b/.")).isEqualTo("h2c://foo/b/");
        assertThat(resolveLocation("/", "b/./")).isEqualTo("h2c://foo/b/");
        assertThat(resolveLocation("/", "b/./c")).isEqualTo("h2c://foo/b/c");

        assertThat(resolveLocation("/a", ".")).isEqualTo("h2c://foo/");
        assertThat(resolveLocation("/a", "b/.")).isEqualTo("h2c://foo/b/");
        assertThat(resolveLocation("/a", "b/./c")).isEqualTo("h2c://foo/b/c");

        assertThat(resolveLocation("/a/", ".")).isEqualTo("h2c://foo/a/");
        assertThat(resolveLocation("/a/", "b/.")).isEqualTo("h2c://foo/a/b/");
        assertThat(resolveLocation("/a/", "b/./c")).isEqualTo("h2c://foo/a/b/c");

        // Double-dot cases
        assertThat(resolveLocation("/", "..")).isNull();
        assertThat(resolveLocation("/", "b/..")).isEqualTo("h2c://foo/");
        assertThat(resolveLocation("/", "b/../")).isEqualTo("h2c://foo/");
        assertThat(resolveLocation("/", "b/../c")).isEqualTo("h2c://foo/c");

        assertThat(resolveLocation("/a", "..")).isNull();
        assertThat(resolveLocation("/a", "b/..")).isEqualTo("h2c://foo/");
        assertThat(resolveLocation("/a", "b/../c")).isEqualTo("h2c://foo/c");

        assertThat(resolveLocation("/a/", "..")).isEqualTo("h2c://foo/");
        assertThat(resolveLocation("/a/", "b/..")).isEqualTo("h2c://foo/a/");
        assertThat(resolveLocation("/a/", "b/../c")).isEqualTo("h2c://foo/a/c");

        // Multiple single- or double- dots
        assertThat(resolveLocation("/", "././a")).isEqualTo("h2c://foo/a");
        assertThat(resolveLocation("/", "a/././b")).isEqualTo("h2c://foo/a/b");
        assertThat(resolveLocation("/", "a/./.")).isEqualTo("h2c://foo/a/");
        assertThat(resolveLocation("/", "a/././")).isEqualTo("h2c://foo/a/");

        assertThat(resolveLocation("/a", "././b")).isEqualTo("h2c://foo/b");
        assertThat(resolveLocation("/a", "b/././c")).isEqualTo("h2c://foo/b/c");
        assertThat(resolveLocation("/a", "b/./.")).isEqualTo("h2c://foo/b/");
        assertThat(resolveLocation("/a", "b/././")).isEqualTo("h2c://foo/b/");

        assertThat(resolveLocation("/a/b/", "../../c")).isEqualTo("h2c://foo/c");
        assertThat(resolveLocation("/a/b/", "c/../../d")).isEqualTo("h2c://foo/a/d");
        assertThat(resolveLocation("/a/b/", "c/../..")).isEqualTo("h2c://foo/a/");
        assertThat(resolveLocation("/a/b/", "c/../../")).isEqualTo("h2c://foo/a/");

        assertThat(resolveLocation("/a/b", "../../c")).isNull();
        assertThat(resolveLocation("/a/b/c", "../../d")).isEqualTo("h2c://foo/d");
        assertThat(resolveLocation("/a/b/c", "d/../../e")).isEqualTo("h2c://foo/a/e");
        assertThat(resolveLocation("/a/b/c", "d/../..")).isEqualTo("h2c://foo/a/");
        assertThat(resolveLocation("/a/b/c", "d/../../")).isEqualTo("h2c://foo/a/");
    }

    @Nullable
    private String resolveLocation(String originalPath, String redirectLocation) {
        final HttpRequest req = HttpRequest.builder()
                                           .get(originalPath)
                                           .header(HttpHeaderNames.AUTHORITY, "foo")
                                           .build();
        final ClientRequestContext ctx = ClientRequestContext.of(req);
        final RequestTarget result = RedirectingClient.resolveLocation(ctx, redirectLocation);
        return result != null ? result.toString() : null;
    }

    private static ClientFactory localhostAccessingClientFactory() {
        return ClientFactory.builder().addressResolverGroupFactory(
                eventLoopGroup -> MockAddressResolverGroup.localhost()).build();
    }
}
