/*
 * Copyright 2022 LINE Corporation
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

import java.util.List;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AsciiString;

class ServiceAdditionalAuthorityTest {

    private static final AsciiString GLOBAL_HEADER = HttpHeaderNames.of("x-global-header");
    private static final AsciiString CUSTOM_HEADER = HttpHeaderNames.of("x-custom-header");

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.addHeader(GLOBAL_HEADER, "all");
            sb.addHeader(CUSTOM_HEADER, "default");
            sb.addHeader(CUSTOM_HEADER, "base");

            sb.service("/", (ctx, req) -> HttpResponse.of("base"));

            sb.virtualHost("foo.com")
              .setHeader(CUSTOM_HEADER, "vhost")
              .route()
              .path("/foo")
              .setHeader(CUSTOM_HEADER, "service")
              .build((ctx, req) -> {
                  return HttpResponse.of("foo");
              })
              .route()
              .path("/bar")
              .decorator((delegate, ctx, req) -> {
                  ctx.addAdditionalResponseHeader(CUSTOM_HEADER, "additional");
                  return delegate.serve(ctx, req);
              })
              .setHeader(CUSTOM_HEADER, "will be overridden")
              .build((ctx, req) -> {
                  return HttpResponse.of("bar");
              })
              .annotatedService()
              .setHeader(CUSTOM_HEADER, "annotated-service")
              .build(new Object() {
                  @Get("/baz")
                  public String bar() {
                      return "baz";
                  }
              });
        }
    };

    @CsvSource({ "127.0.0.1, /, default;base, base",
                 "foo.com, /, vhost, base",
                 "foo.com, /foo, service, foo",
                 "foo.com, /bar, additional, bar",
                 "foo.com, /baz, annotated-service, baz",
    })
    @ParameterizedTest
    void shouldReturnOverriddenHeaders(String host, String path, String headers, String expectedBody) {
        final String[] expectedHeaders = headers.split(";");
        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .addressResolverGroupFactory(unused -> MockAddressResolverGroup.localhost())
                                  .build()) {
            final BlockingWebClient client = WebClient.builder("http://" + host + ':' + server.httpPort())
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            final AggregatedHttpResponse res = client.get(path);
            final List<String> actualHeaders = res.headers().getAll(CUSTOM_HEADER);
            assertThat(actualHeaders).containsExactlyInAnyOrder(expectedHeaders);
            assertThat(res.contentUtf8()).isEqualTo(expectedBody);
            assertThat(res.headers().getAll(GLOBAL_HEADER)).containsExactly("all");
        }
    }
}
