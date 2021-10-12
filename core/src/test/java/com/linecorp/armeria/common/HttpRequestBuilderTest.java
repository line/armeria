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

package com.linecorp.armeria.common;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
import static com.linecorp.armeria.common.HttpHeaderNames.COOKIE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map.Entry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.FixedHttpRequest.EmptyFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.OneElementFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.TwoElementFixedHttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;

import io.netty.util.AsciiString;
import reactor.test.StepVerifier;

class HttpRequestBuilderTest {

    @Test
    void buildSimple() {
        HttpRequest request = HttpRequest.builder().get("/1").build();
        assertThat(request.method()).isEqualTo(HttpMethod.GET);
        assertThat(request.path()).isEqualTo("/1");

        request = HttpRequest.builder().post("/2").build();
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.path()).isEqualTo("/2");

        request = HttpRequest.builder().put("/3").build();
        assertThat(request.method()).isEqualTo(HttpMethod.PUT);
        assertThat(request.path()).isEqualTo("/3");

        request = HttpRequest.builder().delete("/4").build();
        assertThat(request.method()).isEqualTo(HttpMethod.DELETE);
        assertThat(request.path()).isEqualTo("/4");

        request = HttpRequest.builder().patch("/5").build();
        assertThat(request.method()).isEqualTo(HttpMethod.PATCH);
        assertThat(request.path()).isEqualTo("/5");

        request = HttpRequest.builder().options("/6").build();
        assertThat(request.method()).isEqualTo(HttpMethod.OPTIONS);
        assertThat(request.path()).isEqualTo("/6");

        request = HttpRequest.builder().head("/7").build();
        assertThat(request.method()).isEqualTo(HttpMethod.HEAD);
        assertThat(request.path()).isEqualTo("/7");

        request = HttpRequest.builder().trace("/8").build();
        assertThat(request.method()).isEqualTo(HttpMethod.TRACE);
        assertThat(request.path()).isEqualTo("/8");
    }

    @Test
    void shouldAllowEmptyPath() {
        final HttpRequest request = HttpRequest.builder().get("").build();
        assertThat(request.path()).isEqualTo("");
    }

    @Test
    void buildEmptyPathWithQueryParams() {
        final HttpRequest request = HttpRequest.builder().get("").queryParam("foo","bar").build();
        assertThat(request.path()).isEqualTo("?foo=bar");
    }

    @Test
    void buildWithHeaders() {
        final HttpHeaders headers = HttpHeaders.of(AUTHORIZATION, "foo", "bar", "baz");
        final HttpRequest request = HttpRequest.builder().get("/")
                                               .header("x-header", "foo")
                                               .headers(headers)
                                               .build();
        final List<Entry<AsciiString, String>> finalHeaders =
                ImmutableMap.of(AUTHORIZATION, "foo", AsciiString.of("bar"), "baz",
                                AsciiString.of("x-header"), "foo").entrySet().asList();
        assertThat(request.headers()).containsAll(finalHeaders);
        assertThat(request).isInstanceOf(EmptyFixedHttpRequest.class);
    }

    @Test
    void buildWithPathParams() {
        final HttpRequest request = HttpRequest.builder().get("/{foo}/{bar}/:id/foo")
                                               .pathParam("foo", "resource1")
                                               .pathParams(ImmutableMap.of("bar", "resource2", "id", 1))
                                               .build();
        assertThat(request.path()).isEqualTo("/resource1/resource2/1/foo");
        assertThat(request).isInstanceOf(EmptyFixedHttpRequest.class);
    }

    @Test
    void buildWithPathParams2() {
        HttpRequest request = HttpRequest.builder().get("/{foo}/{bar}/:unknown/foo/{unknown}")
                                         .pathParams(ImmutableMap.of("foo", "foo", "bar", "bar"))
                                         .disablePathParams()
                                         .build();
        assertThat(request.path()).isEqualTo("/{foo}/{bar}/:unknown/foo/{unknown}");

        assertThatThrownBy(() -> HttpRequest.builder().get("/{foo}/{bar}/:unknown/foo/{unknown}")
                                            .pathParams(ImmutableMap.of("foo", "foo", "bar", "bar"))
                                            .build())
                .isInstanceOf(IllegalStateException.class);

        request = HttpRequest.builder().get("/{foo}/{bar}/:id/{/foo/}/::/a{")
                             .pathParams(ImmutableMap.of("id", 3, "bar", 2, "foo", 1, "/foo/", 4))
                             .pathParam(":", 5)
                             .build();
        assertThat(request.path()).isEqualTo("/1/2/3/4/5/a{");
    }

    @Test
    void shouldRejectEmptyPathParams() {
        assertThatThrownBy(() -> HttpRequest.builder().pathParam("", "foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");

        assertThatThrownBy(() -> HttpRequest.builder().pathParams(ImmutableMap.of("", "foo")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void shouldFailOnMissingPathParam(boolean curly) {
        assertThatThrownBy(() -> HttpRequest.builder()
                                            .get(curly ? "/{foo}" : "/:foo")
                                            .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("param 'foo'");
    }

    @ParameterizedTest
    @CsvSource({
            "https://host:8080,        https://host:8080",
            "https://host:8080/,       https://host:8080/",
            "https://host:8080/foo,    https://host:8080/foo",
            "https://host:8080/foo/,   https://host:8080/foo/",
            "https://host:8080/:,      https://host:8080/:",
            "https://host:8080/:/,     https://host:8080/:/",
            "https://host:8080/{}/{},  https://host:8080/{}/{}",
            "https://host:8080/{}/:,   https://host:8080/{}/:",
            "https://host:8080/:/{},   https://host:8080/:/{}",
            "https://host:8080/:/:,    https://host:8080/:/:",
            "https://host:8080/{}/{}/, https://host:8080/{}/{}/",
            "https://host:8080/{}/:/,  https://host:8080/{}/:/",
            "https://host:8080/:/{}/,  https://host:8080/:/{}/",
            "https://host:8080/:/:/,   https://host:8080/:/:/",
    })
    void absoluteUriWithoutPathParams(@Nullable String uri, String expectedUri) {
        uri = firstNonNull(uri, "");
        final HttpRequest req = HttpRequest.builder()
                                           .get(uri)
                                           .build();
        assertThat(req.path()).isEqualTo(expectedUri);
    }

    @ParameterizedTest
    @CsvSource({
            "/,           /",
            "/foo,        /foo",
            "/foo/,       /foo/",
            "/{},         /{}",
            "/:,          /:",
            "/{}/,        /{}/",
            "/:/,         /:/",
            "/{}/{},      /{}/{}",
            "/{}/:,       /{}/:",
            "/:/:,        /:/:",
            "/:/{},       /:/{}",
            "/{}/{}/,     /{}/{}/",
            "/{}/:/,      /{}/:/",
            "/:/:/,       /:/:/",
            "/:/{}/,      /:/{}/",
            "/{unmatched, /{unmatched"
    })
    void pathWithoutPathParams(String path, String expectedPath) {
        final HttpRequest req = HttpRequest.builder().get(path).build();
        assertThat(req.path()).isEqualTo(expectedPath);
    }

    @ParameterizedTest
    @CsvSource({
            "https://host:8080/{path},        https://host:8080/ping",
            "https://host:8080/:path,         https://host:8080/ping",
            "https://host:8080/{path}/,       https://host:8080/ping/",
            "https://host:8080/:path/,        https://host:8080/ping/",
            "https://host:8080/{path}/:,      https://host:8080/ping/:",
            "https://host:8080/:path/:,       https://host:8080/ping/:",
            "https://host:8080/{path}/:/,     https://host:8080/ping/:/",
            "https://host:8080/:path/:/,      https://host:8080/ping/:/",
            "https://host:8080/{path}/:/pong, https://host:8080/ping/:/pong",
            "https://host:8080/:path/:/pong,  https://host:8080/ping/:/pong",
            // Note: We don't test '{}' because absolute URIs don't accept '{' or '}'.
    })
    void absoluteUriWithPathParam(String path, String expectedPath) {
        final HttpRequest req = HttpRequest.builder()
                                           .get(path)
                                           .pathParam("path", "ping")
                                           .build();
        assertThat(req.path()).isEqualTo(expectedPath);
    }

    @ParameterizedTest
    @CsvSource({
            "/{path},         /ping",
            "/:path,          /ping",
            "/{path}/,        /ping/",
            "/:path/,         /ping/",
            "/{path}/{},      /ping/{}",
            "/:path/{},       /ping/{}",
            "/{path}/:,       /ping/:",
            "/:path/:,        /ping/:",
            "/{path}/{}/,     /ping/{}/",
            "/:path/{}/,      /ping/{}/",
            "/{path}/:/,      /ping/:/",
            "/:path/:/,       /ping/:/",
            "/{path}/{}/pong, /ping/{}/pong",
            "/:path/{}/pong,  /ping/{}/pong",
            "/{path}/:/pong,  /ping/:/pong",
            "/:path/:/pong,   /ping/:/pong",
    })
    void pathWithPathParam(String path, String expectedPath) {
        final HttpRequest req = HttpRequest.builder()
                                           .get(path)
                                           .pathParam("path", "ping")
                                           .build();
        assertThat(req.path()).isEqualTo(expectedPath);
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void buildWithQueryParams(boolean disablePathParams) {
        final HttpRequestBuilder builder = HttpRequest.builder();
        if (disablePathParams) {
            builder.disablePathParams();
        }

        final HttpRequest req = builder.get("/")
                                           .queryParam("foo", "bar")
                                           .queryParams(QueryParams.of("from", 0, "limit", 10))
                                           .build();
        assertThat(req.path()).isEqualTo("/?foo=bar&from=0&limit=10");
        assertThat(req).isInstanceOf(EmptyFixedHttpRequest.class);
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void buildWithQueryParamsInPath(boolean disablePathParams) {
        final HttpRequestBuilder builder = HttpRequest.builder();
        if (disablePathParams) {
            builder.disablePathParams();
        }

        final HttpRequest req = builder.get("/query?alice=bob").build();
        assertThat(req.path()).isEqualTo("/query?alice=bob");
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void buildWithQueryParamsMixed(boolean disablePathParams) {
        final HttpRequestBuilder builder = HttpRequest.builder();
        if (disablePathParams) {
            builder.disablePathParams();
        }

        final HttpRequest req = builder.get("/query?foo=bar")
                                       .queryParam("alice", "bob")
                                       .build();
        assertThat(req.path()).isEqualTo("/query?foo=bar&alice=bob");
    }

    @ParameterizedTest
    @CsvSource({
            // No query string in path
            "/{path},         /ping?alice=bob",
            "/:path,          /ping?alice=bob",
            "/{path}/,        /ping/?alice=bob",
            "/:path/,         /ping/?alice=bob",
            "/{path}/{},      /ping/{}?alice=bob",
            "/:path/{},       /ping/{}?alice=bob",
            "/{path}/:,       /ping/:?alice=bob",
            "/:path/:,        /ping/:?alice=bob",
            "/{path}/{}/,     /ping/{}/?alice=bob",
            "/:path/{}/,      /ping/{}/?alice=bob",
            "/{path}/:/,      /ping/:/?alice=bob",
            "/:path/:/,       /ping/:/?alice=bob",
            "/{path}/{}/pong, /ping/{}/pong?alice=bob",
            "/:path/{}/pong,  /ping/{}/pong?alice=bob",
            "/{path}/:/pong,  /ping/:/pong?alice=bob",
            "/:path/:/pong,   /ping/:/pong?alice=bob",
            // A query string in path
            "/{path}?foo=bar,         /ping?foo=bar&alice=bob",
            "/:path?foo=bar,          /ping?foo=bar&alice=bob",
            "/{path}/?foo=bar,        /ping/?foo=bar&alice=bob",
            "/:path/?foo=bar,         /ping/?foo=bar&alice=bob",
            "/{path}/{}?foo=bar,      /ping/{}?foo=bar&alice=bob",
            "/:path/{}?foo=bar,       /ping/{}?foo=bar&alice=bob",
            "/{path}/:?foo=bar,       /ping/:?foo=bar&alice=bob",
            "/:path/:?foo=bar,        /ping/:?foo=bar&alice=bob",
            "/{path}/{}/?foo=bar,     /ping/{}/?foo=bar&alice=bob",
            "/:path/{}/?foo=bar,      /ping/{}/?foo=bar&alice=bob",
            "/{path}/:/?foo=bar,      /ping/:/?foo=bar&alice=bob",
            "/:path/:/?foo=bar,       /ping/:/?foo=bar&alice=bob",
            "/{path}/{}/pong?foo=bar, /ping/{}/pong?foo=bar&alice=bob",
            "/:path/{}/pong?foo=bar,  /ping/{}/pong?foo=bar&alice=bob",
            "/{path}/:/pong?foo=bar,  /ping/:/pong?foo=bar&alice=bob",
            "/:path/:/pong?foo=bar,   /ping/:/pong?foo=bar&alice=bob",
    })
    void pathParamAndQueryParams(String path, String expectedPath) {
        final HttpRequest req = HttpRequest.builder()
                                           .get(path)
                                           .pathParam("path", "ping")
                                           .queryParam("alice", "bob")
                                           .build();
        assertThat(req.path()).isEqualTo(expectedPath);
    }

    @Test
    void buildWithCookies() {
        final HttpRequest request = HttpRequest.builder().get("/")
                                               .cookie(Cookie.of("cookie1", "foo"))
                                               .cookies(Cookies.of(Cookie.of("cookie2", "foo"),
                                                                   Cookie.of("cookie3", "foo")))
                                               .build();
        final Cookies cookies = Cookies.of(Cookie.of("cookie1", "foo"),
                                           Cookie.of("cookie2", "foo"),
                                           Cookie.of("cookie3", "foo"));
        assertThat(request.headers().contains(COOKIE, Cookie.toCookieHeader(cookies))).isTrue();
        assertThat(request).isInstanceOf(EmptyFixedHttpRequest.class);
    }

    @Test
    void buildWithTrailers() {
        HttpRequest request = HttpRequest.builder().get("/")
                                         .trailers(HttpHeaders.of("some-trailer", "foo"))
                                         .build();
        assertThat(request).isInstanceOf(OneElementFixedHttpRequest.class);

        request = HttpRequest.builder().get("/")
                             .content(MediaType.PLAIN_TEXT_UTF_8, "foo")
                             .trailers(HttpHeaders.of("some-trailer", "foo"))
                             .build();
        assertThat(request).isInstanceOf(TwoElementFixedHttpRequest.class);
    }

    @Test
    void buildWithStringContent() {
        final HttpRequest request = HttpRequest.builder().post("/")
                                               .content(MediaType.JSON,
                                                        "{\"foo\":\"bar\",\"bar\":\"baz\",\"baz\":1}")
                                               .build();
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.path()).isEqualTo("/");
        assertThat(request.contentType()).isEqualTo(MediaType.JSON);
        StepVerifier.create(request)
                    .expectNext(HttpData.ofUtf8("{\"foo\":\"bar\",\"bar\":\"baz\",\"baz\":1}"))
                    .expectComplete()
                    .verify();
        assertThat(request).isInstanceOf(OneElementFixedHttpRequest.class);
    }

    @Test
    void buildWithFormatContent() {
        final HttpRequest request = HttpRequest.builder().post("/")
                                               .content(MediaType.PLAIN_TEXT_UTF_8, "%s = %d", "foo", 10)
                                               .build();
        assertThat(request.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        StepVerifier.create(request)
                    .expectNext(HttpData.ofUtf8("foo = 10"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void buildWithByteContent() {
        final HttpRequest request = HttpRequest.builder().post("/")
                                               .content(MediaType.PLAIN_TEXT_UTF_8, "abcdefghiklmn".getBytes())
                                               .build();
        assertThat(request.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        StepVerifier.create(request)
                    .expectNext(HttpData.ofUtf8("abcdefghiklmn"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void buildComplex() {
        final HttpRequestBuilder requestBuilder = HttpRequest.builder();
        HttpRequest request = requestBuilder.put("/{foo}/{bar}/:baz/foo-{id}/:boo")
                                            .header("x-header-1", 1234)
                                            .pathParam("foo", "resource1")
                                            .pathParams(ImmutableMap.of("bar", "resource2", "id", 1))
                                            .pathParams(ImmutableMap.of("boo", 2, "baz", "resource3"))
                                            .queryParam("q", "foo")
                                            .content(MediaType.JSON, "foo")
                                            .build();
        assertThat(request.path()).isEqualTo("/resource1/resource2/resource3/foo-1/2?q=foo");
        assertThat(request.headers().contains("x-header-1", "1234")).isTrue();
        assertThat(request.contentType()).isEqualTo(MediaType.JSON);

        requestBuilder.pathParams(ImmutableMap.of("baz", "resource4", "boo", "3", "id", 2))
                      .queryParams(QueryParams.of("q", "bar", "f", 10))
                      .header("x-header-1", 5678)
                      .headers(HttpHeaders.of("x-header-2", "value"))
                      .cookie(Cookie.of("cookie", "value"))
                      .content(MediaType.PLAIN_TEXT_UTF_8, "test");
        request = requestBuilder.build();
        assertThat(request.path()).isEqualTo("/resource1/resource2/resource4/foo-2/3?q=bar&f=10");
        assertThat(request.headers().contains("x-header-1", "5678")).isTrue();
        assertThat(request.headers().contains("x-header-2", "value")).isTrue();
        assertThat(request.headers().contains(COOKIE, "cookie=value")).isTrue();
        assertThat(request.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        StepVerifier.create(request)
                    .expectNext(HttpData.ofUtf8("test"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void buildThrows() {
        final HttpRequestBuilder requestBuilder = HttpRequest.builder();
        assertThatThrownBy(requestBuilder::build).isInstanceOf(IllegalStateException.class)
                                                 .hasMessageContaining("path must be set");
    }

    @Test
    void buildWithPublisher() {
        final HttpRequest request =
                HttpRequest.builder()
                           .method(HttpMethod.GET)
                           .path("/")
                           .content(MediaType.PLAIN_TEXT_UTF_8, StreamMessage.of(HttpData.ofUtf8("hello")))
                           .build();

        StepVerifier.create(request)
                    .expectNext(HttpData.ofUtf8("hello"))
                    .verifyComplete();

        final HttpRequest requestWithTrailers =
                HttpRequest.builder()
                           .method(HttpMethod.GET)
                           .path("/")
                           .content(MediaType.PLAIN_TEXT_UTF_8, StreamMessage.of(HttpData.ofUtf8("hello")))
                           .trailers(HttpHeaders.of("foo", "bar"))
                           .build();

        StepVerifier.create(requestWithTrailers)
                    .expectNext(HttpData.ofUtf8("hello"))
                    .expectNext(HttpHeaders.of("foo", "bar"))
                    .verifyComplete();
    }
}
