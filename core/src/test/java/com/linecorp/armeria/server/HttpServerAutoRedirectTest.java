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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

/**
 * Makes sure that {@link Server} sends a redirect response for the paths without a trailing slash,
 * only when there's a {@link Route} for the path with a trailing slash.
 */
class HttpServerAutoRedirectTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final HttpService service = (ctx, req) -> HttpResponse.of(500);
            // `/a` should be redirected to `/a/`.
            sb.service("/a/", service);

            // `/b` should be redirected to `/b/`.
            sb.service("prefix:/b/", service);

            // `/c/1` should be redirected to `/c/1/`.
            sb.service("/c/{value}/", service);

            // `/d` should NOT be redirected because `/d` has a mapping.
            sb.service("/d", (ctx, req) -> HttpResponse.of(200));
            sb.service("prefix:/d/", service);

            // `GET /e` should be redirected to `/e/`.
            // `DELETE /e` should NOT be redirected to `/e/`.
            sb.route().get("/e/").build(service);

            // `/f` should be redirected to `/f/`.
            // The decorator at `/f/` should NOT be evaluated during redirection, because the route doesn't
            // match yet. However, if a client sends a request to `/f/`, the decorator will be evaluated,
            // returning `202 Accepted`.
            sb.service("/f/", service);
            sb.routeDecorator().pathPrefix("/f/").build((delegate, ctx, req) -> HttpResponse.of(202));
        }
    };

    @Test
    void redirection() {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        AggregatedHttpResponse res;

        res = client.get("/a");
        assertThat(res.status()).isSameAs(HttpStatus.TEMPORARY_REDIRECT);
        assertThat(res.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("a/");

        res = client.get("/b");
        assertThat(res.status()).isSameAs(HttpStatus.TEMPORARY_REDIRECT);
        assertThat(res.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("b/");

        res = client.get("/c/1");
        assertThat(res.status()).isSameAs(HttpStatus.TEMPORARY_REDIRECT);
        assertThat(res.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("1/");

        res = client.get("/d");
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        res = client.get("/e");
        assertThat(res.status()).isSameAs(HttpStatus.TEMPORARY_REDIRECT);
        assertThat(res.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("e/");

        res = client.delete("/e");
        assertThat(res.status()).isSameAs(HttpStatus.NOT_FOUND);

        res = client.get("/f");
        assertThat(res.status()).isSameAs(HttpStatus.TEMPORARY_REDIRECT);
        assertThat(res.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("f/");

        res = client.get("/f/");
        assertThat(res.status()).isSameAs(HttpStatus.ACCEPTED);
    }
}
