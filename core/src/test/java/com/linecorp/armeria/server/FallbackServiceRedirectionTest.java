/*
 * Copyright 2023 LINE Corporation
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

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class FallbackServiceRedirectionTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final HttpService service = (ctx, req) -> HttpResponse.of(req.path());
            sb.serviceUnder("/service", service);
            sb.serviceUnder("/service%2F", service);
            sb.serviceUnder("/foo/bar", service);
            sb.serviceUnder("/foo/bar%2F", service);
        }
    };

    @RegisterExtension
    static final ServerExtension proxy = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.serviceUnder("/proxy", (ctx, req) -> {
                final HttpRequest request = req.withHeaders(req.headers()
                                                               .toBuilder()
                                                               .path(req.path().replace("/proxy", ""))
                                                               .build());
                ctx.updateRequest(request);
                return server.webClient().execute(request);
            });
        }
    };

    @CsvSource({
            "/service, service/",
            "/service?a=b, service/?a=b",
            "/service%2F, service%2F/",
            "/service%2F?a=b, service%2F/?a=b",
            "/foo/bar, bar/",
            "/foo/bar?a=b, bar/?a=b",
            "/foo/bar%2F, bar%2F/",
            "/foo/bar%2F?a=b, bar%2F/?a=b"
    })
    @ParameterizedTest
    void redirection(String path, String redirectLocation) {
        final BlockingWebClient client = server.blockingWebClient();
        final AggregatedHttpResponse response = client.get(path);
        assertThat(response.headers().get(HttpHeaderNames.LOCATION)).isEqualTo(redirectLocation);
    }

    @CsvSource({
            "/proxy/service, /service/",
            "/proxy/service?a=b, /service/?a=b",
            "/proxy/service%2F, /service%2F/",
            "/proxy/service%2F?a=b, /service%2F/?a=b",
            "/proxy/foo/bar, /foo/bar/",
            "/proxy/foo/bar?a=b, /foo/bar/?a=b",
            "/proxy/foo/bar%2F, /foo/bar%2F/",
            "/proxy/foo/bar%2F?a=b, /foo/bar%2F/?a=b"
    })
    @ParameterizedTest
    void redirectingClient(String path, String changedPath) {
        final BlockingWebClient client =
                proxy.blockingWebClient(builder -> builder.followRedirects(RedirectConfig.of()));
        final AggregatedHttpResponse response = client.get(path);
        assertThat(response.contentUtf8()).isEqualTo(changedPath);
    }
}
