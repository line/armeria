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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RedirectingClientTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.tlsSelfSigned();
            sb.http(0);
            sb.https(0);
            sb.service("/foo", (ctx, req) -> HttpResponse.ofRedirect("/fooRedirect1"))
              .service("/fooRedirect1", (ctx, req) -> HttpResponse.ofRedirect("/fooRedirect2"))
              .service("/fooRedirect2", (ctx, req) -> HttpResponse.of("fooRedirection2"));

            sb.service("/https", (ctx, req) -> HttpResponse.ofRedirect(
                    "https://127.0.0.1:" + server.httpsPort() + "/httpsRedirect"))
              .service("/httpsRedirect", (ctx, req) -> {
                  assertThat(ctx.sessionProtocol()).isSameAs(SessionProtocol.H2);
                  return HttpResponse.of("httpsRedirection");
              });

            sb.service("/seeOther", (ctx, req) -> HttpResponse.from(
                    req.aggregate().thenApply(aggregatedReq -> {
                        assertThat(aggregatedReq.contentUtf8()).isEqualTo("hello!");
                        return HttpResponse.ofRedirect(HttpStatus.SEE_OTHER, "/seeOtherRedirect");
                    })))
              .service("/seeOtherRedirect", (ctx, req) -> {
                  assertThat(ctx.method()).isSameAs(HttpMethod.GET);
                  return HttpResponse.of("seeOtherRedirection");
              });

            sb.service("/anotherDomain", (ctx, req) -> HttpResponse.ofRedirect(
                    "http://foo.com:" + server.httpPort() + "/anotherDomainRedirect"));
            sb.virtualHost("foo.com").service("/anotherDomainRedirect",
                                              (ctx, req) -> HttpResponse.of("anotherDomainRedirection"));

            sb.service("/removeDotSegments/foo", (ctx, req) -> HttpResponse.ofRedirect("./bar"))
              .service("/removeDotSegments/bar", (ctx, req) -> HttpResponse.of("removeDotSegmentsRedirection"));

            sb.service("/loop", (ctx, req) -> HttpResponse.ofRedirect("loop1"))
              .service("/loop1", (ctx, req) -> HttpResponse.ofRedirect("loop2"))
              .service("/loop2", (ctx, req) -> HttpResponse.ofRedirect("loop"));

            sb.service("/differentHttpMethod", (ctx, req) -> {
                if (ctx.method() == HttpMethod.GET) {
                    return HttpResponse.of("differentHttpMethod");
                } else {
                    assertThat(ctx.method()).isSameAs(HttpMethod.POST);
                    return HttpResponse.ofRedirect(HttpStatus.SEE_OTHER, "/differentHttpMethod");
                }
            });
        }
    };

    @Test
    void redirect_successWithRetry() {
        final AggregatedHttpResponse res = sendRequest(2);
        assertThat(res.contentUtf8()).isEqualTo("fooRedirection2");
    }

    private static AggregatedHttpResponse sendRequest(int maxRedirects) {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .redirectConfig(RedirectConfig.builder()
                                                                        .maxRedirects(maxRedirects)
                                                                        .build())
                                          .build();
        return client.get("/foo").aggregate().join();
    }

    @Test
    void redirect_failExceedingTotalAttempts() {
        final AggregatedHttpResponse res = sendRequest(1);
        assertThat(res.status()).isSameAs(HttpStatus.TEMPORARY_REDIRECT);
        assertThat(res.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("/fooRedirect2");
    }

    @Test
    void httpsRedirect() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(ClientFactory.insecure())
                                          .followRedirects()
                                          .build();
        final AggregatedHttpResponse join = client.get("/https").aggregate().join();
        assertThat(join.contentUtf8()).isEqualTo("httpsRedirection");
    }

    @Test
    void seeOtherHttpMethodChangedToGet() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .followRedirects()
                                          .build();
        final AggregatedHttpResponse join = client.post("/seeOther", "hello!").aggregate().join();
        assertThat(join.contentUtf8()).isEqualTo("seeOtherRedirection");
    }

    @Test
    void webClientCreatedWithBaseUri_doesNotAllowRedirectionToOtherDomain() {
        try (ClientFactory factory = mockClientFactory()) {
            WebClient client = WebClient.builder(server.httpUri())
                                        .factory(factory)
                                        .followRedirects()
                                        .build();
            AggregatedHttpResponse join = client.get("/anotherDomain").aggregate().join();
            assertThat(join.status()).isSameAs(HttpStatus.TEMPORARY_REDIRECT);
            assertThat(join.headers().get(HttpHeaderNames.LOCATION)).contains("/anotherDomainRedirect");

            client = WebClient.builder(server.httpUri())
                              .factory(factory)
                              .redirectConfig(RedirectConfig.builder().allowDomains("foo.com").build())
                              .build();

            join = client.get("/anotherDomain").aggregate().join();
            assertThat(join.contentUtf8()).isEqualTo("anotherDomainRedirection");
        }
    }

    @Test
    void webClientCreatedWithoutBaseUri_allowRedirectionToOtherDomain() {
        try (ClientFactory factory = mockClientFactory()) {
            final WebClient client = WebClient.builder()
                                              .factory(factory)
                                              .followRedirects()
                                              .build();
            final AggregatedHttpResponse join = client.get(server.httpUri() + "/anotherDomain")
                                                      .aggregate()
                                                      .join();
            assertThat(join.contentUtf8()).isEqualTo("anotherDomainRedirection");
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
        final AggregatedHttpResponse join = client.get("/removeDotSegments/foo").aggregate().join();
        assertThat(join.contentUtf8()).isEqualTo("removeDotSegmentsRedirection");
    }

    @Test
    void redirectLoopsException() {
        final WebClient client = Clients.builder(server.httpUri())
                                        .followRedirects()
                                        .build(WebClient.class);
        assertThatThrownBy(() -> client.get("/loop").aggregate().join()).hasMessageContainingAll(
                "The initial request path: /loop, redirect paths:", "/loop1", "/loop2");
    }

    @Test
    void notRedirectLoopsWhenHttpMethodDiffers() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .followRedirects()
                                          .build();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.post("/differentHttpMethod", HttpData.empty()).aggregate().join().contentUtf8())
                    .isEqualTo("differentHttpMethod");
            assertThat(captor.get().log().whenComplete().join().children().size()).isEqualTo(2);
        }
    }

    private static ClientFactory mockClientFactory() {
        return ClientFactory.builder().addressResolverGroupFactory(
                eventLoopGroup -> MockAddressResolverGroup.localhost()).build();
    }
}
