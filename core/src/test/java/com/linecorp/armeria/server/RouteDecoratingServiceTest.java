/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RouteDecoratingServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of("Hello, world!"));
            sb.routeDecorator()
              .methods(HttpMethod.TRACE)
              .pathPrefix("/")
              .build((delegate, ctx, req) -> HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
        }
    };

    @Test
    void routeDecorator() throws Exception {
        final WebClient webClient = WebClient.of(server.httpUri());
        // This GET request doesn't go through the decorator.
        final HttpResponse response1 = webClient.execute(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(response1.aggregate().get().status()).isEqualTo(HttpStatus.OK);

        final HttpResponse response2 = webClient.execute(HttpRequest.of(HttpMethod.TRACE, "/"));
        assertThat(response2.aggregate().get().status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void routeDecorator_notExistApi() throws Exception {
        final WebClient webClient = WebClient.of(server.httpUri());
        // This GET request doesn't go through the decorator.
        final HttpResponse response1 = webClient.execute(HttpRequest.of(HttpMethod.GET, "/not_exist"));
        assertThat(response1.aggregate().get().status()).isEqualTo(HttpStatus.NOT_FOUND);

        final HttpResponse response2 = webClient.execute(HttpRequest.of(HttpMethod.TRACE, "/not_exist"));
        assertThat(response2.aggregate().get().status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void preflight() {
        final WebClient webClient = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res1 = webClient.execute(preflightHeaders("/")).aggregate().join();
        assertThat(res1.headers().status()).isSameAs(HttpStatus.OK);

        final AggregatedHttpResponse res2 = webClient.execute(preflightHeaders("/not_exist"))
                                                     .aggregate()
                                                     .join();
        assertThat(res2.headers().status()).isSameAs(HttpStatus.FORBIDDEN);
    }

    private static RequestHeaders preflightHeaders(String path) {
        return RequestHeaders.of(HttpMethod.OPTIONS, path,
                                 HttpHeaderNames.ACCEPT, "utf-8",
                                 HttpHeaderNames.ORIGIN, "http://example.com",
                                 HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "GET");
    }
}
