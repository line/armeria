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

import io.netty.util.AsciiString;

public class HttpRequestBuilderTest {

    @Test
    void buildSimple() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder();
        final HttpRequest request = requestBuilder.method(HttpMethod.GET)
                                                  .path("/")
                                                  .build();
        assertThat(request.headers().method()).isEqualTo(HttpMethod.GET);
        assertThat(request.headers().path()).isEqualTo("/");
        assertThat(request).isInstanceOf(EmptyFixedHttpRequest.class);
    }

    @Test
    void buildWithHeaders() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder();
        final HttpHeaders headers = HttpHeaders.of("authorization", "foo", "bar", "baz");
        final HttpRequest request = requestBuilder.method(HttpMethod.GET)
                                                  .path("/")
                                                  .header("x-header", "foo")
                                                  .headers(headers)
                                                  .build();
        assertThat(request.headers().method()).isEqualTo(HttpMethod.GET);
        assertThat(request.headers().path()).isEqualTo("/");
        final List<Entry<AsciiString, String>> list = ImmutableMap.of(AsciiString.of("authorization"), "foo",
                                                                      AsciiString.of("bar"), "baz",
                                                                      AsciiString.of("x-header"), "foo")
                                                                  .entrySet().asList();
        assertThat(request.headers()).containsAll(list);
    }

    @Test
    void buildWithQueryParams() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder();
        final HttpRequest request = requestBuilder.method(HttpMethod.GET)
                                                  .path("/")
                                                  .queryParam("foo", "bar")
                                                  .queryParams(QueryParams.of("from", 0, "limit", 10))
                                                  .build();
        assertThat(request.headers().method()).isEqualTo(HttpMethod.GET);
        assertThat(request.headers().path()).isEqualTo("/?foo=bar&from=0&limit=10");
    }

    @Test
    void buildWithPathParams() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder();
        final HttpRequest request = requestBuilder.method(HttpMethod.GET)
                                                  .path("/{foo}/{bar}/{id}/foo")
                                                  .pathParam("foo", "resource1")
                                                  .pathParams(ImmutableMap.of("bar", "resource2", "id", "1"))
                                                  .build();
        assertThat(request.headers().method()).isEqualTo(HttpMethod.GET);
        assertThat(request.headers().path()).isEqualTo("/resource1/resource2/1/foo");
    }

    @Test
    void buildWithCookies() {
        final HttpRequestBuilder requestBuilder = new HttpRequestBuilder();
        final HttpRequest request = requestBuilder.method(HttpMethod.GET)
                                                  .path("/")
                                                  .cookie(Cookie.of("cookie1", "foo"))
                                                  .cookies(Cookies.of(Cookie.of("cookie2", "foo"),
                                                                      Cookie.of("cookie3", "foo")))
                                                  .build();
        final Cookies cookies = Cookies.of(Cookie.of("cookie1", "foo"),
                                           Cookie.of("cookie2", "foo"),
                                           Cookie.of("cookie3", "foo"));
        assertThat(request.headers().method()).isEqualTo(HttpMethod.GET);
        assertThat(request.headers().path()).isEqualTo("/");
        assertThat(request.headers().contains(COOKIE, Cookie.toCookieHeader(cookies))).isTrue();
    }
}
