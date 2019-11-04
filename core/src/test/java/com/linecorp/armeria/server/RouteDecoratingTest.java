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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.testing.internal.MockAddressResolverGroup;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class RouteDecoratingTest {

    static Queue<Integer> queue = new ArrayDeque<>();
    private static final String ACCESS_TOKEN = "bearer: access-token";

    @BeforeEach
    void init() {
        queue = new ArrayDeque<>();
    }

    static DecoratingServiceFunction<HttpRequest, HttpResponse> newDecorator(int id) {
        return (delegate, ctx, req) -> {
            queue.add(id);
            return delegate.serve(ctx, req);
        };
    }

    @RegisterExtension
    static ServerExtension decoratingServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/abc", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .service("/abc/def", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .service("/abc/def/ghi", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .routeDecorator().pathPrefix("/").build(newDecorator(1))
              .routeDecorator().path("/abc").build(newDecorator(2))
              .routeDecorator().pathPrefix("/abc").build(newDecorator(3))
              .routeDecorator().pathPrefix("/abc/def").build(newDecorator(4))
              .routeDecorator().path("glob:**def").build(newDecorator(5));
        }
    };

    @RegisterExtension
    static ServerExtension authServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/api/users/{id}", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .annotatedService("/api/admin", new Object() {
                  @Get("/{id}")
                  public String getRole(@Param("id") Integer id) {
                      return "ADMIN";
                  }
              })
              .serviceUnder("/assets", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .decorator(Route.builder().pathPrefix("/api").build(), (delegate, ctx, req) -> {
                  if (!ACCESS_TOKEN.equals(req.headers().get(HttpHeaderNames.AUTHORIZATION))) {
                      return HttpResponse.of(HttpStatus.UNAUTHORIZED);
                  }
                  return delegate.serve(ctx, req);
              })
              .routeDecorator()
              .pathPrefix("/assets/resources")
              .build((delegate, ctx, req) -> {
                  final HttpResponse response = delegate.serve(ctx, req);
                  ctx.addAdditionalResponseHeader(HttpHeaderNames.CACHE_CONTROL, "public");
                  return response;
              })
              .routeDecorator()
              .pathPrefix("/assets/resources/private")
              .build((delegate, ctx, req) -> {
                  final HttpResponse response = delegate.serve(ctx, req);
                  ctx.removeAdditionalResponseHeader(HttpHeaderNames.CACHE_CONTROL);
                  ctx.addAdditionalResponseHeader(HttpHeaderNames.CACHE_CONTROL, "private");
                  return response;
              });
        }
    };

    @RegisterExtension
    static ServerExtension virtualHostServer = new ServerExtension() {
        @Override
        protected void configure(final ServerBuilder sb) throws Exception {
            sb.virtualHost("foo.com")
              .routeDecorator()
              .pathPrefix("/foo")
              .build((delegate, ctx, req) -> {
                  if (!ACCESS_TOKEN.equals(req.headers().get(HttpHeaderNames.AUTHORIZATION))) {
                      return HttpResponse.of(HttpStatus.UNAUTHORIZED);
                  }
                  return delegate.serve(ctx, req);
              })
              .serviceUnder("/foo", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .and()
              .virtualHost("bar.com")
              .serviceUnder("/bar", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @ParameterizedTest
    @MethodSource("generateDecorateInOrder")
    void decorateInOrder(String path, List<Integer> orders) {
        final HttpClient client = HttpClient.of(decoratingServer.uri("/"));
        client.get(path).aggregate().join();
        assertThat(queue).containsExactlyElementsOf(orders);
    }

    static Stream<Arguments> generateDecorateInOrder() {
        return Stream.of(
                Arguments.of("/abc", ImmutableList.of(1, 2)),
                Arguments.of("/abc/def", ImmutableList.of(1, 3, 5)),
                Arguments.of("/abc/def/ghi", ImmutableList.of(1, 3, 4)));
    }

    @ParameterizedTest
    @CsvSource({
            "/api/users/1, " + ACCESS_TOKEN + ", 200, ",
            "/api/users/2, " + ACCESS_TOKEN + ", 200, ",
            "/api/users/1, , 401, ",
            "/api/users/2, , 401, ",
            "/api/admin/1, " + ACCESS_TOKEN + ", 200, ",
            "/api/admin/1, , 401, ",
            "/assets/index.html, , 200, ",
            "/assets/resources/index.html, , 200, public",
            "/assets/resources/private/profile.jpg, , 200, private",
    })
    void secured(String path, @Nullable String authorization, int status, String cacheControl) {
        final HttpClient client = HttpClient.of(authServer.uri("/"));
        final RequestHeaders headers;
        if (authorization != null) {
            headers = RequestHeaders.of(HttpMethod.GET, path, HttpHeaderNames.AUTHORIZATION, authorization);
        } else {
            headers = RequestHeaders.of(HttpMethod.GET, path);
        }
        final AggregatedHttpResponse res = client.execute(headers).aggregate().join();
        assertThat(res.status().code()).isEqualTo(status);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo(cacheControl);
    }

    @ParameterizedTest
    @CsvSource({
            "foo.com, /foo/1, " + ACCESS_TOKEN + ", 200",
            "foo.com, /foo/1, , 401",
            "bar.com, /bar/1, , 200"
    })
    void virtualHost(String host, String path, @Nullable String authorization, int status) {
        final ClientFactory factory =
                ClientFactory.builder()
                             .addressResolverGroupFactory(eventLoop -> MockAddressResolverGroup.localhost())
                             .build();
        final HttpClient client = HttpClient.of(factory, "http://" + host + ':' + virtualHostServer.httpPort());
        final RequestHeaders headers;
        if (authorization != null) {
            headers = RequestHeaders.of(HttpMethod.GET, path, HttpHeaderNames.AUTHORIZATION, authorization);
        } else {
            headers = RequestHeaders.of(HttpMethod.GET, path);
        }
        final AggregatedHttpResponse res = client.execute(headers).aggregate().join();
        assertThat(res.status().code()).isEqualTo(status);
    }

    @Test
    void shouldSetPath() {
        assertThatThrownBy(() -> Server.builder().routeDecorator().build(Function.identity()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Should set at least one");
    }
}
