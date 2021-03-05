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

import java.util.Map;

import org.reactivestreams.Publisher;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

/**
 * Builds a new {@link HttpRequest}.
 */
public final class HttpRequestBuilder extends AbstractHttpRequestBuilder {

    HttpRequestBuilder() {}

    /**
     * Builds the request.
     */
    public HttpRequest build() {
        return buildRequest();
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public HttpRequestBuilder get(String path) {
        return (HttpRequestBuilder) super.get(path);
    }

    @Override
    public HttpRequestBuilder post(String path) {
        return (HttpRequestBuilder) super.post(path);
    }

    @Override
    public HttpRequestBuilder put(String path) {
        return (HttpRequestBuilder) super.put(path);
    }

    @Override
    public HttpRequestBuilder delete(String path) {
        return (HttpRequestBuilder) super.delete(path);
    }

    @Override
    public HttpRequestBuilder patch(String path) {
        return (HttpRequestBuilder) super.patch(path);
    }

    @Override
    public HttpRequestBuilder options(String path) {
        return (HttpRequestBuilder) super.options(path);
    }

    @Override
    public HttpRequestBuilder head(String path) {
        return (HttpRequestBuilder) super.head(path);
    }

    @Override
    public HttpRequestBuilder trace(String path) {
        return (HttpRequestBuilder) super.trace(path);
    }

    @Override
    public HttpRequestBuilder method(HttpMethod method) {
        return (HttpRequestBuilder) super.method(method);
    }

    @Override
    public HttpRequestBuilder path(String path) {
        return (HttpRequestBuilder) super.path(path);
    }

    @Override
    public HttpRequestBuilder content(MediaType contentType, CharSequence content) {
        return (HttpRequestBuilder) super.content(contentType, content);
    }

    @Override
    public HttpRequestBuilder content(MediaType contentType, String content) {
        return (HttpRequestBuilder) super.content(contentType, content);
    }

    @Override
    @FormatMethod
    public HttpRequestBuilder content(MediaType contentType, @FormatString String format,
                                      Object... content) {
        return (HttpRequestBuilder) super.content(contentType, format, content);
    }

    @Override
    public HttpRequestBuilder content(MediaType contentType, byte[] content) {
        return (HttpRequestBuilder) super.content(contentType, content);
    }

    @Override
    public HttpRequestBuilder content(MediaType contentType, HttpData content) {
        return (HttpRequestBuilder) super.content(contentType, content);
    }

    @Override
    public HttpRequestBuilder content(MediaType contentType, Publisher<? extends HttpData> publisher) {
        return (HttpRequestBuilder) super.content(contentType, publisher);
    }

    @Override
    public HttpRequestBuilder header(CharSequence name, Object value) {
        return (HttpRequestBuilder) super.header(name, value);
    }

    @Override
    public HttpRequestBuilder headers(
            Iterable<? extends Map.Entry<? extends CharSequence, String>> headers) {
        return (HttpRequestBuilder) super.headers(headers);
    }

    @Override
    public HttpRequestBuilder trailers(
            Iterable<? extends Map.Entry<? extends CharSequence, String>> trailers) {
        return (HttpRequestBuilder) super.trailers(trailers);
    }

    @Override
    public HttpRequestBuilder pathParam(String name, Object value) {
        return (HttpRequestBuilder) super.pathParam(name, value);
    }

    @Override
    public HttpRequestBuilder pathParams(Map<String, ?> pathParams) {
        return (HttpRequestBuilder) super.pathParams(pathParams);
    }

    @Override
    public HttpRequestBuilder disablePathParams() {
        return (HttpRequestBuilder) super.disablePathParams();
    }

    @Override
    public HttpRequestBuilder queryParam(String name, Object value) {
        return (HttpRequestBuilder) super.queryParam(name, value);
    }

    @Override
    public HttpRequestBuilder queryParams(
            Iterable<? extends Map.Entry<? extends String, String>> queryParams) {
        return (HttpRequestBuilder) super.queryParams(queryParams);
    }

    @Override
    public HttpRequestBuilder cookie(Cookie cookie) {
        return (HttpRequestBuilder) super.cookie(cookie);
    }

    @Override
    public HttpRequestBuilder cookies(Iterable<? extends Cookie> cookies) {
        return (HttpRequestBuilder) super.cookies(cookies);
    }
}
