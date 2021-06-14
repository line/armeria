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
package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RedirectRuleTest {

    private static final AtomicInteger counter = new AtomicInteger();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.tlsSelfSigned();
            sb.http(0);
            sb.https(0);
            sb.service("/foo", (ctx, req) -> {
                final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.MOVED_PERMANENTLY,
                                                                   HttpHeaderNames.LOCATION, "/fooRedirect");
                return HttpResponse.of(headers);
            });
            sb.service("/fooRedirect", (ctx, req) -> {
                if (counter.getAndIncrement() < 1) {
                    return HttpResponse.of(500);
                }
                return HttpResponse.of("fooRedirection");
            });

            sb.service("/https", (ctx, req) -> {
                final ResponseHeaders headers =
                        ResponseHeaders.of(HttpStatus.MOVED_PERMANENTLY,
                                           HttpHeaderNames.LOCATION,
                                           "https://127.0.0.1:" + server.httpsPort() + "/httpsRedirect");
                return HttpResponse.of(headers);
            });
            sb.service("/httpsRedirect", (ctx, req) -> {
                assertThat(ctx.sessionProtocol()).isSameAs(SessionProtocol.H2);
                System.err.println(ctx.sessionProtocol());
                return HttpResponse.of("httpsRedirection");
            });

            sb.service("/seeOther", (ctx, req) -> HttpResponse.from(
                    req.aggregate().thenApply(aggregatedReq -> {
                        assertThat(aggregatedReq.contentUtf8()).isEqualTo("hello!");
                        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.SEE_OTHER,
                                                                           HttpHeaderNames.LOCATION,
                                                                           "/seeOtherRedirect");
                        return HttpResponse.of(headers);
                    })));
            sb.service("/seeOtherRedirect", (ctx, req) -> {
                assertThat(ctx.method()).isSameAs(HttpMethod.GET);
                return HttpResponse.of("seeOtherRedirection");
            });

            sb.service("/loop", (ctx, req) -> {
                final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.MULTIPLE_CHOICES,
                                                                   HttpHeaderNames.LOCATION, "/loop1");
                return HttpResponse.of(headers);
            });
            sb.service("/loop1", (ctx, req) -> {
                final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.FOUND,
                                                                   HttpHeaderNames.LOCATION, "/loop2");
                return HttpResponse.of(headers);
            });
            sb.service("/loop2", (ctx, req) -> {
                final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.TEMPORARY_REDIRECT,
                                                                   HttpHeaderNames.LOCATION, "/loop");
                return HttpResponse.of(headers);
            });
        }
    };

    @BeforeEach
    void setUp() {
        counter.set(0);
    }

    @Test
    void redirect_successWithRetry() {
        final AggregatedHttpResponse res = sendRequest(2);
        assertThat(res.contentUtf8()).contains("fooRedirect");
    }

    private static AggregatedHttpResponse sendRequest(int totalAttempts) {
        final RetryConfig<HttpResponse> config =
                RetryConfig.builder(RetryRule.redirect().orElse(RetryRule.onServerErrorStatus()))
                           .maxTotalAttempts(totalAttempts)
                           .build();
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(RetryingClient.newDecorator(config))
                                          .build();
        return client.get("/foo").aggregate().join();
    }

    @Test
    void redirect_failExceedingTotalAttempts() {
        final AggregatedHttpResponse res = sendRequest(1);
        assertThat(res.status()).isSameAs(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void httpsRedirect() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(ClientFactory.insecure())
                                          .decorator(RetryingClient.newDecorator(RetryRule.redirect()))
                                          .build();
        final AggregatedHttpResponse join = client.get("/https").aggregate().join();
        assertThat(join.contentUtf8()).contains("httpsRedirection");
    }

    @Test
    void seeOtherHttpMethodChangedToGet() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(RetryingClient.newDecorator(RetryRule.redirect()))
                                          .build();
        final AggregatedHttpResponse join = client.post("/seeOther", "hello!").aggregate().join();
        assertThat(join.contentUtf8()).contains("seeOtherRedirection");
    }

    @Test
    void redirectLoopsException() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(RetryingClient.newDecorator(RetryRule.redirect()))
                                          .build();
        assertThatThrownBy(() -> client.get("/loop").aggregate().join())
                .hasMessageContainingAll("originalPath: /loop, redirect paths:", "loop1", "loop2");
    }
}
