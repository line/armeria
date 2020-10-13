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

import static com.linecorp.armeria.common.HttpHeaderNames.COOKIE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map.Entry;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.FixedHttpRequest.EmptyFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.OneElementFixedHttpRequest;
import com.linecorp.armeria.internal.common.DefaultHttpRequest;

import io.netty.util.AsciiString;
import reactor.test.StepVerifier;

public class HttpRequestBuilderTest {

    @Test
    void factoryMethods() {
        final HttpRequest getRequest = HttpRequest.get("/1").build();
        assertThat(getRequest.method()).isEqualTo(HttpMethod.GET);
        assertThat(getRequest.path()).isEqualTo("/1");

        final HttpRequest postRequest = HttpRequest.post("/2").build();
        assertThat(postRequest.method()).isEqualTo(HttpMethod.POST);
        assertThat(postRequest.path()).isEqualTo("/2");

        final HttpRequest putRequest = HttpRequest.put("/3").build();
        assertThat(putRequest.method()).isEqualTo(HttpMethod.PUT);
        assertThat(putRequest.path()).isEqualTo("/3");
    }

    @Test
    void buildSimple() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder();
        final HttpRequest request = requestBuilder.method(HttpMethod.GET)
                                                  .path("/")
                                                  .build();
        assertThat(request.method()).isEqualTo(HttpMethod.GET);
        assertThat(request.path()).isEqualTo("/");
        assertThat(request).isInstanceOf(EmptyFixedHttpRequest.class);
    }

    @Test
    void buildStreaming() {
        final HttpRequest request = new HttpRequestBuilder(HttpMethod.GET, "/").streaming().build();
        assertThat(request.method()).isEqualTo(HttpMethod.GET);
        assertThat(request.path()).isEqualTo("/");
        assertThat(request).isInstanceOf(DefaultHttpRequest.class);
    }

    @Test
    void buildWithHeaders() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder(HttpMethod.GET, "/");
        final HttpHeaders headers = HttpHeaders.of("authorization", "foo", "bar", "baz");
        final HttpRequest request = requestBuilder.header("x-header", "foo")
                                                  .headers(headers)
                                                  .build();
        final List<Entry<AsciiString, String>> list = ImmutableMap.of(AsciiString.of("authorization"), "foo",
                                                                      AsciiString.of("bar"), "baz",
                                                                      AsciiString.of("x-header"), "foo")
                                                                  .entrySet().asList();
        assertThat(request.headers()).containsAll(list);
        assertThat(request).isInstanceOf(EmptyFixedHttpRequest.class);
    }

    @Test
    void buildWithQueryParams() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder(HttpMethod.GET, "/");
        final HttpRequest request = requestBuilder.queryParam("foo", "bar")
                                                  .queryParams(QueryParams.of("from", 0, "limit", 10))
                                                  .build();
        assertThat(request.path()).isEqualTo("/?foo=bar&from=0&limit=10");
        assertThat(request).isInstanceOf(EmptyFixedHttpRequest.class);
    }

    @Test
    void buildWithPathParams() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder();
        final HttpRequest request = requestBuilder.method(HttpMethod.GET)
                                                  .path("/{foo}/{bar}/{id}/foo")
                                                  .pathParam("foo", "resource1")
                                                  .pathParams(ImmutableMap.of("bar", "resource2", "id", "1"))
                                                  .build();
        assertThat(request.path()).isEqualTo("/resource1/resource2/1/foo");
        assertThat(request).isInstanceOf(EmptyFixedHttpRequest.class);
    }

    @Test
    void buildWithCookies() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder(HttpMethod.GET, "/");
        final HttpRequest request = requestBuilder.cookie(Cookie.of("cookie1", "foo"))
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
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder(HttpMethod.POST, "/");
        final HttpRequest request = requestBuilder.content(MediaType.JSON,
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
}
