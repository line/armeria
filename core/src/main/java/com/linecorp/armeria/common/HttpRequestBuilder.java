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

import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_LENGTH;
import static com.linecorp.armeria.common.HttpHeaderNames.COOKIE;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.FixedHttpRequest.EmptyFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.OneElementFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.TwoElementFixedHttpRequest;
import com.linecorp.armeria.internal.common.DefaultHttpRequest;

import io.netty.util.AsciiString;

/**
 * Builds a new {@link HttpRequest}.
 */
final class HttpRequestBuilder {

    private final List<Cookie> cookies = new ArrayList<>();
    private final Map<String, String> pathParams = new HashMap<>();
    private HttpHeaders httpHeaders = HttpHeaders.of();
    private HttpHeaders httpTrailers = HttpHeaders.of();
    private QueryParams queryParams = QueryParams.of();
    private HttpData content = HttpData.empty();
    @Nullable
    private HttpMethod method;
    @Nullable
    private String path;
    @Nullable
    private MediaType mediaType;
    private boolean streaming;

    HttpRequestBuilder() {
    }

    HttpRequestBuilder(HttpMethod method, String path) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        this.method = method;
        this.path = path;
    }

    /**
     * Sets the method for this request.
     * @see HttpMethod
     */
    public HttpRequestBuilder method(HttpMethod method) {
        requireNonNull(method, "method");
        this.method = method;
        return this;
    }

    /**
     * Sets the path for this request.
     */
    public HttpRequestBuilder path(String path) {
        requireNonNull(path, "path");
        this.path = path;
        return this;
    }

    /**
     * Sets the content for this request.
     */
    public HttpRequestBuilder content(MediaType mediaType, CharSequence content) {
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        this.mediaType = mediaType;
        this.content = HttpData.of(mediaType.charset(StandardCharsets.UTF_8), content);
        return this;
    }

    /**
     * Sets the content for this request.
     */
    public HttpRequestBuilder content(MediaType mediaType, String content) {
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        this.mediaType = mediaType;
        this.content = HttpData.of(mediaType.charset(StandardCharsets.UTF_8), content);
        return this;
    }

    /**
     * Sets the content for this request. The content of the request is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @FormatMethod
    public HttpRequestBuilder content(MediaType mediaType, @FormatString String format, Object... content) {
        requireNonNull(mediaType, "mediaType");
        requireNonNull(format, "format");
        this.mediaType = mediaType;
        this.content = HttpData.of(mediaType.charset(StandardCharsets.UTF_8), format, content);
        return this;
    }

    /**
     * Sets the content for this request.
     */
    public HttpRequestBuilder content(MediaType mediaType, byte[] content) {
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        this.mediaType = mediaType;
        this.content = HttpData.wrap(content);
        return this;
    }

    /**
     * Sets the content for this request.
     */
    public HttpRequestBuilder content(MediaType mediaType, HttpData content) {
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        this.mediaType = mediaType;
        this.content = content;
        return this;
    }

    /**
     * Sets a header for this request. For example:
     * <pre>{@code
     * HttpRequest.get("/")
     *            .header("authorization", "foo")
     *            .build();
     * }</pre>
     */
    public HttpRequestBuilder header(String name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        httpHeaders = httpHeaders.toBuilder().add(name, value).build();
        return this;
    }

    /**
     * Sets multiple headers for this request. For example:
     * <pre>{@code
     * HttpRequest.get("/")
     *            .headers(HttpHeaders.of("authorization", "foo", "bar", "baz"))
     *            .build();
     * }</pre>
     * @see HttpHeaders
     */
    public HttpRequestBuilder headers(HttpHeaders httpHeaders) {
        requireNonNull(httpHeaders, "httpHeaders");
        final HttpHeadersBuilder httpHeadersBuilder = this.httpHeaders.toBuilder();
        for (AsciiString name : httpHeaders.names()) {
            httpHeadersBuilder.add(name, requireNonNull(httpHeaders.get(name), "header"));
        }
        this.httpHeaders = httpHeadersBuilder.build();
        return this;
    }

    /**
     * Sets HTTP trailers for this request.
     */
    public HttpRequestBuilder trailers(HttpHeaders httpTrailers) {
        requireNonNull(httpTrailers, "httpTrailers");
        final HttpHeadersBuilder httpTrailersBuilder = this.httpTrailers.toBuilder();
        for (AsciiString name : httpTrailers.names()) {
            httpTrailersBuilder.add(name, requireNonNull(httpTrailers.get(name), "trailer"));
        }
        this.httpTrailers = httpTrailersBuilder.build();
        return this;
    }

    /**
     * Sets a path param for this request. For example:
     * <pre>{@code
     * HttpRequest.get("/{foo}")
     *            .pathParam("foo", "bar")
     *            .build(); // GET `/bar`
     * }</pre>
     */
    public HttpRequestBuilder pathParam(String name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        pathParams.put(name, value);
        return this;
    }

    /**
     * Sets multiple path params for this request. For example:
     * <pre>{@code
     * HttpRequest.get("/{foo}/{bar}")
     *            .pathParams(Map.of("foo", "bar", "bar", "baz"))
     *            .build(); // GET `/bar/baz`
     * }</pre>
     */
    public HttpRequestBuilder pathParams(Map<String, String> pathParams) {
        requireNonNull(pathParams, "pathParams");
        this.pathParams.putAll(pathParams);
        return this;
    }

    /**
     * Sets a query param for this request. For example:
     * <pre>{@code
     * HttpRequest.get("/endpoint")
     *            .queryParam("foo", "bar")
     *            .build(); // GET `/endpoint?foo=bar`
     * }</pre>
     */
    public HttpRequestBuilder queryParam(String name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        queryParams = queryParams.toBuilder().add(name, value).build();
        return this;
    }

    /**
     * Sets multiple query params for this request. For example:
     * <pre>{@code
     * HttpRequest.get("/endpoint")
     *            .queryParams(QueryParams.of("from", "foo", "limit", 10))
     *            .build(); // GET `/endpoint?from=foo&limit=10`
     * }</pre>
     * @see QueryParams
     */
    public HttpRequestBuilder queryParams(QueryParams queryParams) {
        requireNonNull(queryParams, "queryParams");
        final QueryParamsBuilder queryParamsBuilder = this.queryParams.toBuilder();
        for (String name : queryParams.names()) {
            queryParamsBuilder.add(name, requireNonNull(queryParams.get(name), "query"));
        }
        this.queryParams = queryParamsBuilder.build();
        return this;
    }

    /**
     * Sets a cookie for this request. For example:
     * <pre>{@code
     * HttpRequest.get("/")
     *            .cookie(Cookie.of("cookie", "foo"))
     *            .build();
     * }</pre>
     * @see Cookie
     */
    public HttpRequestBuilder cookie(Cookie cookie) {
        requireNonNull(cookie, "cookie");
        cookies.add(cookie);
        return this;
    }

    /**
     * Sets multiple cookies for this request. For example:
     * <pre>{@code
     * HttpRequest.get("/")
     *            .cookies(Cookies.of(Cookie.of("cookie1", "foo"),
     *                                Cookie.of("cookie2", "bar")))
     *            .build();
     * }</pre>
     * @see Cookies
     */
    public HttpRequestBuilder cookies(Cookies cookies) {
        requireNonNull(cookies, "cookies");
        this.cookies.addAll(cookies);
        return this;
    }

    /**
     * Makes this request a streaming request.
     */
    public HttpRequestBuilder streaming() {
        this.streaming = true;
        return this;
    }

    /**
     * Creates a new {@link HttpRequest}.
     */
    public HttpRequest build() {
        if (!pathParams.isEmpty()) {
            path = replacePathParams();
        }
        if (!queryParams.isEmpty()) {
            path += "?" + queryParams.toQueryString();
        }
        final RequestHeaders requestHeaders = buildRequestHeader();
        if (streaming) {
            return new DefaultHttpRequest(requestHeaders);
        }
        if (content.length() == 0) {
            content.close();
            if (httpTrailers.isEmpty()) {
                return new EmptyFixedHttpRequest(requestHeaders);
            } else {
                return new OneElementFixedHttpRequest(requestHeaders, httpTrailers);
            }
        }
        if (httpTrailers.isEmpty()) {
            return new OneElementFixedHttpRequest(requestHeaders, content);
        } else {
            return new TwoElementFixedHttpRequest(requestHeaders, content, httpTrailers);
        }
    }

    private RequestHeaders buildRequestHeader() {
        requireNonNull(path, "path");
        requireNonNull(method, "method");
        final RequestHeadersBuilder requestHeadersBuilder = RequestHeaders.builder().method(method).path(path);
        if (mediaType != null) {
            requestHeadersBuilder.contentType(mediaType);
        }
        if (!httpHeaders.isEmpty()) {
            for (AsciiString name : httpHeaders.names()) {
                requestHeadersBuilder.add(name, requireNonNull(httpHeaders.get(name), "header"));
            }
        }
        if (!cookies.isEmpty()) {
            requestHeadersBuilder.add(COOKIE, Cookie.toCookieHeader(cookies));
        }
        if (content.length() == 0) {
            requestHeadersBuilder.remove(CONTENT_LENGTH);
        } else {
            requestHeadersBuilder.setInt(CONTENT_LENGTH, content.length());
        }
        return requestHeadersBuilder.build();
    }

    private String replacePathParams() {
        requireNonNull(path, "path");
        int i = 0;
        final StringBuilder pathBuilder = new StringBuilder(path);
        while (i < pathBuilder.length()) {
            if (pathBuilder.charAt(i) == '{') {
                int j = i;
                while (j < pathBuilder.length() && pathBuilder.charAt(j) != '}') {
                    j++;
                }
                if (j == pathBuilder.length()) {
                    break;
                }
                final String name = pathBuilder.substring(i + 1, j);
                if (pathParams.containsKey(name)) {
                    pathBuilder.replace(i, j + 1, pathParams.get(name));
                    i += pathParams.get(name).length() - 1;
                }
            }
            i++;
        }
        return pathBuilder.toString();
    }
}
