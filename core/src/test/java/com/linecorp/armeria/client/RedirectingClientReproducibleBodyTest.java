/*
 * Copyright 2026 LINE Corporation
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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RedirectingClientReproducibleBodyTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/first", (ctx, req) -> HttpResponse.of(
                    ResponseHeaders.of(HttpStatus.TEMPORARY_REDIRECT,
                                       HttpHeaderNames.LOCATION, "/second")));
            sb.service("/second", (ctx, req) -> HttpResponse.of(
                    req.aggregate().thenApply(agg ->
                            AggregatedHttpResponse.of(HttpStatus.OK, ctx.request().contentType(),
                                                      agg.contentUtf8()).toHttpResponse())));
        }
    };

    @Test
    void factoryRegeneratesBodyForRedirect() {
        final AtomicInteger factoryCalls = new AtomicInteger();
        final Supplier<HttpRequest> factory = () -> {
            factoryCalls.incrementAndGet();
            return HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/first",
                                                    HttpHeaderNames.CONTENT_TYPE,
                                                    "text/plain; charset=utf-8"),
                                  StreamMessage.of(HttpData.ofUtf8("redir-body")));
        };

        final WebClient client =
                WebClient.builder(server.httpUri())
                         .followRedirects()
                         .build();

        final RequestOptions options =
                RequestOptions.builder()
                              .exchangeType(ExchangeType.REQUEST_STREAMING)
                              .attr(ClientRequestBodyFactory.REQUEST_BODY_FACTORY, factory)
                              .build();

        final AggregatedHttpResponse res =
                client.execute(factory.get(), options).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("redir-body");
        // factory.get() once for the initial request + once for the redirected hop.
        assertThat(factoryCalls).hasValueGreaterThanOrEqualTo(2);
    }
}
