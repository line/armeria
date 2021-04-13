/*
 * Copyright 2021 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AbstractHttpRequestBuilderTest {

    @Test
    void pathBuilder_relativePath() {
        final AbstractHttpRequestBuilder builder = new AbstractHttpRequestBuilder() {};
        final String path = "/foo";
        final HttpRequest httpRequest = builder.get(path).buildRequest();
        assertThat(httpRequest.path()).isEqualTo(path);
    }

    @Test
    void pathBuilder_relativePath_withPathParam() {
        final AbstractHttpRequestBuilder builder = new AbstractHttpRequestBuilder() {};
        final String path = "/foo/:bar";
        final HttpRequest httpRequest = builder.get(path)
                                               .pathParam("bar", "quz")
                                               .buildRequest();
        assertThat(httpRequest.path()).isEqualTo("/foo/quz");
    }

    @Test
    void pathBuilder_noHeadingSlash_withPathParam() {
        final AbstractHttpRequestBuilder builder = new AbstractHttpRequestBuilder() {};
        final String path = ":foo/bar";
        final HttpRequest httpRequest = builder.get(path)
                                               .pathParam("foo", "quz")
                                               .buildRequest();
        assertThat(httpRequest.path()).isEqualTo("quz/bar");
    }

    @Test
    void pathBuilder_acceptColon() {
        final AbstractHttpRequestBuilder builder = new AbstractHttpRequestBuilder() {};
        final String path = "/foo/:/bar";
        final HttpRequest httpRequest = builder.get(path)
                                               .buildRequest();
        assertThat(httpRequest.path()).isEqualTo("/foo/:/bar");
    }

    @Test
    void pathBuilder_relativePath_withPathParamAndQueryString() {
        final AbstractHttpRequestBuilder builder = new AbstractHttpRequestBuilder() {};
        final String path = "/foo/:bar?foo=:bar#fragment";
        final HttpRequest httpRequest = builder.get(path)
                                               .pathParam("bar", "quz")
                                               .buildRequest();
        assertThat(httpRequest.path()).isEqualTo("/foo/quz?foo=:bar#fragment");
    }

    @Test
    void pathBuilder_relativePath_withPathParamAndFragment() {
        final AbstractHttpRequestBuilder builder = new AbstractHttpRequestBuilder() {};
        final String path = "/foo/bar#foo=:bar";
        final HttpRequest httpRequest = builder.get(path)
                                               .pathParam("bar", "quz")
                                               .buildRequest();
        assertThat(httpRequest.path()).isEqualTo("/foo/bar#foo=:bar");
    }

    @Test
    void pathBuilder_absolutePath() {
        final AbstractHttpRequestBuilder builder = new AbstractHttpRequestBuilder() {};
        final String path = "https://armeria.dev";
        final HttpRequest httpRequest = builder.get(path).buildRequest();
        assertThat(httpRequest.path()).isEqualTo(path);
    }

    @Test
    void pathBuilder_absolutePath_withPort() {
        final AbstractHttpRequestBuilder builder = new AbstractHttpRequestBuilder() {};
        final String path = "https://armeria.dev:8443/abc";
        final HttpRequest httpRequest = builder.get(path).buildRequest();
        assertThat(httpRequest.path()).isEqualTo(path);
    }

    @Test
    void pathBuilder_absolutePath_withAdditionalQueryParams() {
        AbstractHttpRequestBuilder builder = new AbstractHttpRequestBuilder() {};
        String path = "https://armeria.dev:8443/abc?a=b";
        HttpRequest httpRequest = builder.get(path)
                                         .queryParam("c", "d")
                                         .buildRequest();
        assertThat(httpRequest.path()).isEqualTo(path + "&c=d");

        builder = new AbstractHttpRequestBuilder() {};
        path = "https://armeria.dev:8443/abc?a=b#fragment";
        httpRequest = builder.get(path)
                             .queryParam("c", "d")
                             .buildRequest();
        assertThat(httpRequest.path()).isEqualTo("https://armeria.dev:8443/abc?a=b&c=d#fragment");
    }

    @Test
    void pathBuilder_absolutePath_withQueryParams() {
        final AbstractHttpRequestBuilder builder = new AbstractHttpRequestBuilder() {};
        final String path = "https://armeria.dev:8443/abc";
        final HttpRequest httpRequest = builder.get(path)
                                               .queryParam("c", "d")
                                               .buildRequest();
        assertThat(httpRequest.path()).isEqualTo(path + "?c=d");
    }

    @Test
    void pathBuilder_absolutePath_withPathParams() {
        final AbstractHttpRequestBuilder builder = new AbstractHttpRequestBuilder() {};
        final String path = "https://armeria.dev:8443/abc/:def";
        final HttpRequest httpRequest = builder.get(path)
                                               .pathParam("def", "foo")
                                               .buildRequest();
        assertThat(httpRequest.path()).isEqualTo("https://armeria.dev:8443/abc/foo");
    }

    @Test
    void pathBuilder_absolutePath_withPathParamsAndQueryString() {
        final AbstractHttpRequestBuilder builder = new AbstractHttpRequestBuilder() {};
        final String path = "https://armeria.dev:8443/abc/:def?:def=bar#fragment";
        final HttpRequest httpRequest = builder.get(path)
                                               .pathParam("def", "foo")
                                               .buildRequest();
        assertThat(httpRequest.path()).isEqualTo("https://armeria.dev:8443/abc/foo?:def=bar#fragment");
    }
}
