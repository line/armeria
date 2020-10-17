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

import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;
import static com.linecorp.armeria.common.HttpHeaderNames.COOKIE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map.Entry;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.FixedHttpRequest.EmptyFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.OneElementFixedHttpRequest;

import io.netty.util.AsciiString;
import reactor.test.StepVerifier;

public class HttpRequestBuilderTest {

    @Test
    void buildSimple() {
        final HttpRequest getRequest = HttpRequest.builder().get("/1").build();
        assertThat(getRequest.method()).isEqualTo(HttpMethod.GET);
        assertThat(getRequest.path()).isEqualTo("/1");

        final HttpRequest postRequest = HttpRequest.builder().post("/2").build();
        assertThat(postRequest.method()).isEqualTo(HttpMethod.POST);
        assertThat(postRequest.path()).isEqualTo("/2");

        final HttpRequest putRequest = HttpRequest.builder().put("/3").build();
        assertThat(putRequest.method()).isEqualTo(HttpMethod.PUT);
        assertThat(putRequest.path()).isEqualTo("/3");
    }

    @Test
    void buildWithHeaders() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder();
        final HttpHeaders headers = HttpHeaders.of(AUTHORIZATION, "foo", "bar", "baz");
        final HttpRequest request = requestBuilder.get("/")
                                                  .header("x-header", "foo")
                                                  .headers(headers)
                                                  .build();
        final List<Entry<AsciiString, String>> list = ImmutableMap.of(AUTHORIZATION, "foo",
                                                                      AsciiString.of("bar"), "baz",
                                                                      AsciiString.of("x-header"), "foo")
                                                                  .entrySet().asList();
        assertThat(request.headers()).containsAll(list);
        assertThat(request).isInstanceOf(EmptyFixedHttpRequest.class);
    }

    @Test
    void buildWithQueryParams() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder();
        final HttpRequest request = requestBuilder.get("/")
                                                  .queryParam("foo", "bar")
                                                  .queryParams(QueryParams.of("from", 0, "limit", 10))
                                                  .build();
        assertThat(request.path()).isEqualTo("/?foo=bar&from=0&limit=10");
        assertThat(request).isInstanceOf(EmptyFixedHttpRequest.class);
    }

    @Test
    void buildWithPathParams() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder();
        final HttpRequest request = requestBuilder.get("/{foo}/{bar}/:id/foo")
                                                  .pathParam("foo", "resource1")
                                                  .pathParams(ImmutableMap.of("bar", "resource2", "id", 1))
                                                  .build();
        assertThat(request.path()).isEqualTo("/resource1/resource2/1/foo");
        assertThat(request).isInstanceOf(EmptyFixedHttpRequest.class);
    }

    @Test
    void buildWithCookies() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder();
        final HttpRequest request = requestBuilder.get("/")
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
    void buildWithContent() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder();
        final HttpRequest request = requestBuilder.post("/")
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
    void buildComplex() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder();
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
}
