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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.client.redirect.TooManyRedirectsException;
import com.linecorp.armeria.client.redirect.UnexpectedDomainRedirectException;
import com.linecorp.armeria.client.redirect.UnexpectedProtocolRedirectException;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
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

            sb.service("/seeOther", (ctx, req) -> HttpResponse.from(
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
            sb.virtualHost("foo.com").service("/anotherDomainRedirect", (ctx, req) -> HttpResponse.of(200));

            sb.service("/removeDotSegments/foo", (ctx, req) -> HttpResponse.ofRedirect("./bar"))
              .service("/removeDotSegments/bar", (ctx, req) -> HttpResponse.of(200));

            sb.service("/loop", (ctx, req) -> HttpResponse.ofRedirect("loop1"))
              .service("/loop1", (ctx, req) -> HttpResponse.ofRedirect("loop2"))
              .service("/loop2", (ctx, req) -> HttpResponse.ofRedirect("loop"));

            sb.service("/differentHttpMethod", (ctx, req) -> {
                if (ctx.method() == HttpMethod.GET) {
                    return HttpResponse.of(200);
                } else {
                    assertThat(ctx.method()).isSameAs(HttpMethod.POST);
                    return HttpResponse.ofRedirect(HttpStatus.SEE_OTHER, "/differentHttpMethod");
                }
            });
        }

        private int otherHttpPort(ServiceRequestContext ctx) {
            final Optional<ServerPort> serverPort =
                    server.server().activePorts().values()
                          .stream().filter(ServerPort::hasHttp)
                          .filter(port -> port.localAddress().getPort() !=
                                          ((InetSocketAddress) ctx.localAddress()).getPort())
                          .findFirst();
            assert serverPort.isPresent();
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
        assertThatThrownBy(() -> sendRequest(1)).hasCauseInstanceOf(TooManyRedirectsException.class)
                                                .hasMessageContaining("maxRedirects: 1");
    }

    private static AggregatedHttpResponse sendRequest(int maxRedirects) {
        final WebClient client = WebClient.builder(server.httpUri())
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
                                          .followRedirects()
                                          .build();
        assertThatThrownBy(() -> client.get("/protocolChange").aggregate().join())
                .hasCauseInstanceOf(UnexpectedProtocolRedirectException.class)
                .hasMessageContaining("redirectProtocol: https (expected: [http])");

        requestCounter.set(0);
        final WebClient client1 = WebClient.builder(server.httpUri())
                                           .factory(ClientFactory.insecure())
                                           .followRedirects(RedirectConfig.builder()
                                                                          .allowProtocols(SessionProtocol.HTTPS)
                                                                          .build())
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
                    .hasMessageContaining("foo.com is not allowed to redirect.");

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
        final AggregatedHttpResponse res = client.get("/removeDotSegments/foo").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
    }

    @Test
    void cyclicRedirectsException() {
        final WebClient client = Clients.builder(server.httpUri())
                                        .followRedirects()
                                        .build(WebClient.class);
        assertThatThrownBy(() -> client.get("/loop").aggregate().join()).hasMessageContainingAll(
                "The original URI:", "/loop", "redirect URIs:", "/loop1", "/loop2");
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

    private static ClientFactory localhostAccessingClientFactory() {
        return ClientFactory.builder().addressResolverGroupFactory(
                eventLoopGroup -> MockAddressResolverGroup.localhost()).build();
    }
}
