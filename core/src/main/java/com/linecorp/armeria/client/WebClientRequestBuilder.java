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

package com.linecorp.armeria.client;

import java.util.Map;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestBuilder;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;

/**
 * Builds a new {@link HttpRequest} for a {@link WebClient}.
 */
public final class WebClientRequestBuilder extends HttpRequestBuilder {

    private final WebClient client;

    WebClientRequestBuilder(WebClient client) {
        this.client = client;
    }

    /**
     * Builds and executes the request.
     */
    public HttpResponse execute() {
        return client.execute(build());
    }

    // Override the return types of the chaining methods in the superclass.

    @Override
    public WebClientRequestBuilder get(String path) {
        return (WebClientRequestBuilder) super.get(path);
    }

    @Override
    public WebClientRequestBuilder post(String path) {
        return (WebClientRequestBuilder) super.post(path);
    }

    @Override
    public WebClientRequestBuilder put(String path) {
        return (WebClientRequestBuilder) super.put(path);
    }

    @Override
    public WebClientRequestBuilder delete(String path) {
        return (WebClientRequestBuilder) super.delete(path);
    }

    @Override
    public WebClientRequestBuilder patch(String path) {
        return (WebClientRequestBuilder) super.patch(path);
    }

    @Override
    public WebClientRequestBuilder options(String path) {
        return (WebClientRequestBuilder) super.options(path);
    }

    @Override
    public WebClientRequestBuilder head(String path) {
        return (WebClientRequestBuilder) super.head(path);
    }

    @Override
    public WebClientRequestBuilder trace(String path) {
        return (WebClientRequestBuilder) super.trace(path);
    }

    @Override
    public WebClientRequestBuilder method(HttpMethod method) {
        return (WebClientRequestBuilder) super.method(method);
    }

    @Override
    public WebClientRequestBuilder path(String path) {
        return (WebClientRequestBuilder) super.path(path);
    }

    @Override
    public WebClientRequestBuilder content(MediaType contentType, CharSequence content) {
        return (WebClientRequestBuilder) super.content(contentType, content);
    }

    @Override
    public WebClientRequestBuilder content(MediaType contentType, String content) {
        return (WebClientRequestBuilder) super.content(contentType, content);
    }

    @Override
    @FormatMethod
    public WebClientRequestBuilder content(MediaType contentType, @FormatString String format,
                                           Object... content) {
        return (WebClientRequestBuilder) super.content(contentType, format, content);
    }

    @Override
    public WebClientRequestBuilder content(MediaType contentType, byte[] content) {
        return (WebClientRequestBuilder) super.content(contentType, content);
    }

    @Override
    public WebClientRequestBuilder content(MediaType contentType, HttpData content) {
        return (WebClientRequestBuilder) super.content(contentType, content);
    }

    @Override
    public WebClientRequestBuilder header(CharSequence name, Object value) {
        return (WebClientRequestBuilder) super.header(name, value);
    }

    @Override
    public WebClientRequestBuilder headers(
            Iterable<? extends Map.Entry<? extends CharSequence, String>> headers) {
        return (WebClientRequestBuilder) super.headers(headers);
    }

    @Override
    public WebClientRequestBuilder trailers(
            Iterable<? extends Map.Entry<? extends CharSequence, String>> trailers) {
        return (WebClientRequestBuilder) super.trailers(trailers);
    }

    @Override
    public WebClientRequestBuilder pathParam(String name, Object value) {
        return (WebClientRequestBuilder) super.pathParam(name, value);
    }

    @Override
    public WebClientRequestBuilder pathParams(Map<String, ?> pathParams) {
        return (WebClientRequestBuilder) super.pathParams(pathParams);
    }

    @Override
    public WebClientRequestBuilder disablePathParams() {
        return (WebClientRequestBuilder) super.disablePathParams();
    }

    @Override
    public WebClientRequestBuilder queryParam(String name, Object value) {
        return (WebClientRequestBuilder) super.queryParam(name, value);
    }

    @Override
    public WebClientRequestBuilder queryParams(
            Iterable<? extends Map.Entry<? extends String, String>> queryParams) {
        return (WebClientRequestBuilder) super.queryParams(queryParams);
    }

    @Override
    public WebClientRequestBuilder cookie(Cookie cookie) {
        return (WebClientRequestBuilder) super.cookie(cookie);
    }

    @Override
    public WebClientRequestBuilder cookies(Iterable<? extends Cookie> cookies) {
        return (WebClientRequestBuilder) super.cookies(cookies);
    }
}
