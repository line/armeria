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
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
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
            sb.service("/foo", (ctx, req) -> {
                final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.MOVED_PERMANENTLY,
                                                                   HttpHeaderNames.LOCATION, "/fooRedirect1");
                return HttpResponse.of(headers);
            }).service("/fooRedirect1", (ctx, req) -> {
                final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.MOVED_PERMANENTLY,
                                                                   HttpHeaderNames.LOCATION, "/fooRedirect2");
                return HttpResponse.of(headers);
            }).service("/fooRedirect2", (ctx, req) -> HttpResponse.of("fooRedirect2"));

            sb.service("/https", (ctx, req) -> {
                final ResponseHeaders headers =
                        ResponseHeaders.of(HttpStatus.MOVED_PERMANENTLY,
                                           HttpHeaderNames.LOCATION,
                                           "https://127.0.0.1:" + server.httpsPort() + "/httpsRedirect");
                return HttpResponse.of(headers);
            }).service("/httpsRedirect", (ctx, req) -> {
                assertThat(ctx.sessionProtocol()).isSameAs(SessionProtocol.H2);
                return HttpResponse.of("httpsRedirection");
            });

            sb.service("/seeOther", (ctx, req) -> HttpResponse.from(
                    req.aggregate().thenApply(aggregatedReq -> {
                        assertThat(aggregatedReq.contentUtf8()).isEqualTo("hello!");
                        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.SEE_OTHER,
                                                                           HttpHeaderNames.LOCATION,
                                                                           "/seeOtherRedirect");
                        return HttpResponse.of(headers);
                    })))
              .service("/seeOtherRedirect", (ctx, req) -> {
                  assertThat(ctx.method()).isSameAs(HttpMethod.GET);
                  return HttpResponse.of("seeOtherRedirection");
              });

            sb.service("/anotherDomain", (ctx, req) -> {
                final ResponseHeaders headers =
                        ResponseHeaders.of(HttpStatus.MOVED_PERMANENTLY, HttpHeaderNames.LOCATION,
                                           "http://foo.com:" + server.httpPort() + "/anotherDomainRedirect");
                return HttpResponse.of(headers);
            });
            sb.virtualHost("foo.com").service("/anotherDomainRedirect",
                                              (ctx, req) -> HttpResponse.of("anotherDomainRedirection"));

            sb.service("/removeDotSegments/foo", (ctx, req) -> {
                final ResponseHeaders headers =
                        ResponseHeaders.of(HttpStatus.MOVED_PERMANENTLY,
                                           HttpHeaderNames.LOCATION, "./bar");
                return HttpResponse.of(headers);
            }).service("/removeDotSegments/bar", (ctx, req) -> HttpResponse.of("removeDotSegmentsRedirection"));

            sb.service("/loop", (ctx, req) -> {
                final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.MULTIPLE_CHOICES,
                                                                   HttpHeaderNames.LOCATION, "/loop1");
                return HttpResponse.of(headers);
            }).service("/loop1", (ctx, req) -> {
                final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.FOUND,
                                                                   HttpHeaderNames.LOCATION, "/loop2");
                return HttpResponse.of(headers);
            }).service("/loop2", (ctx, req) -> {
                final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.TEMPORARY_REDIRECT,
                                                                   HttpHeaderNames.LOCATION, "/loop");
                return HttpResponse.of(headers);
            });
        }
    };

    @Test
    void redirect_successWithRetry() {
        final AggregatedHttpResponse res = sendRequest(2);
        assertThat(res.contentUtf8()).contains("fooRedirect2");
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
        assertThat(res.status()).isSameAs(HttpStatus.MOVED_PERMANENTLY);
    }

    @Test
    void httpsRedirect() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(ClientFactory.insecure())
                                          .enableRedirect()
                                          .build();
        final AggregatedHttpResponse join = client.get("/https").aggregate().join();
        assertThat(join.contentUtf8()).contains("httpsRedirection");
    }

    @Test
    void seeOtherHttpMethodChangedToGet() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .enableRedirect()
                                          .build();
        final AggregatedHttpResponse join = client.post("/seeOther", "hello!").aggregate().join();
        assertThat(join.contentUtf8()).contains("seeOtherRedirection");
    }

    @Test
    void webClientCreatedWithBaseUri_doesNotAllowRedirectionToOtherDomain() {
        WebClient client = WebClient.builder(server.httpUri())
                                    .factory(mockClientFactory())
                                    .enableRedirect()
                                    .build();
        AggregatedHttpResponse join = client.get("/anotherDomain").aggregate().join();
        assertThat(join.status()).isSameAs(HttpStatus.MOVED_PERMANENTLY);
        assertThat(join.headers().get(HttpHeaderNames.LOCATION)).contains("/anotherDomainRedirect");

        client = WebClient.builder(server.httpUri())
                          .factory(mockClientFactory())
                          .redirectConfig(RedirectConfig.builder().allow("foo.com").build())
                          .build();

        join = client.get("/anotherDomain").aggregate().join();
        assertThat(join.contentUtf8()).isEqualTo("anotherDomainRedirection");
    }

    @Test
    void webClientCreatedWithoutBaseUri_allowRedirectionToOtherDomain() {
        final WebClient client = WebClient.builder()
                                          .factory(mockClientFactory())
                                          .enableRedirect()
                                          .build();
        final AggregatedHttpResponse join = client.get(server.httpUri() + "/anotherDomain").aggregate().join();
        assertThat(join.contentUtf8()).isEqualTo("anotherDomainRedirection");
    }

    @Test
    void referenceResolution() {
        // /a/b with ./c is resolved to a/c
        // See https://datatracker.ietf.org/doc/html/rfc3986#section-5.2.4
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(ClientFactory.insecure())
                                          .enableRedirect()
                                          .build();
        final AggregatedHttpResponse join = client.get("/removeDotSegments/foo").aggregate().join();
        assertThat(join.contentUtf8()).contains("removeDotSegmentsRedirection");
    }

    @Test
    void redirectLoopsException() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .enableRedirect()
                                          .build();
        assertThatThrownBy(() -> client.get("/loop").aggregate().join()).hasMessageContainingAll(
                "The initial request path: /loop, redirect paths:", "/loop1", "/loop2");
    }

    private static ClientFactory mockClientFactory() {
        return ClientFactory.builder().addressResolverGroupFactory(
                eventLoopGroup -> MockAddressResolverGroup.localhost()).build();
    }
}
