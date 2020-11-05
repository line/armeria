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

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_LENGTH;
import static com.linecorp.armeria.common.HttpHeaderNames.COOKIE;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.FixedHttpRequest.EmptyFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.OneElementFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.TwoElementFixedHttpRequest;

/**
 * Builds a new {@link HttpRequest}.
 */
public class HttpRequestBuilder {

    // TODO(tumile): Add content(Publisher).

    private final RequestHeadersBuilder requestHeadersBuilder = RequestHeaders.builder();
    private final HttpHeadersBuilder httpTrailersBuilder = HttpHeaders.builder();
    private final QueryParamsBuilder queryParamsBuilder = QueryParams.builder();
    private final Map<String, Object> pathParams = new HashMap<>();
    private final List<Cookie> cookies = new ArrayList<>();
    @Nullable
    private HttpData content;
    @Nullable
    private String path;
    private boolean disablePathParams;

    protected HttpRequestBuilder() {}

    /**
     * Shortcut to set GET method and path.
     */
    public HttpRequestBuilder get(String path) {
        return method(HttpMethod.GET).path(path);
    }

    /**
     * Shortcut to set POST method and path.
     */
    public HttpRequestBuilder post(String path) {
        return method(HttpMethod.POST).path(path);
    }

    /**
     * Shortcut to set PUT method and path.
     */
    public HttpRequestBuilder put(String path) {
        return method(HttpMethod.PUT).path(path);
    }

    /**
     * Shortcut to set DELETE method and path.
     */
    public HttpRequestBuilder delete(String path) {
        return method(HttpMethod.DELETE).path(path);
    }

    /**
     * Shortcut to set PATCH method and path.
     */
    public HttpRequestBuilder patch(String path) {
        return method(HttpMethod.PATCH).path(path);
    }

    /**
     * Shortcut to set OPTIONS method and path.
     */
    public HttpRequestBuilder options(String path) {
        return method(HttpMethod.OPTIONS).path(path);
    }

    /**
     * Shortcut to set HEAD method and path.
     */
    public HttpRequestBuilder head(String path) {
        return method(HttpMethod.HEAD).path(path);
    }

    /**
     * Shortcut to set TRACE method and path.
     */
    public HttpRequestBuilder trace(String path) {
        return method(HttpMethod.TRACE).path(path);
    }

    /**
     * Sets the method for this request.
     * @see HttpMethod
     */
    public HttpRequestBuilder method(HttpMethod method) {
        requestHeadersBuilder.method(requireNonNull(method, "method"));
        return this;
    }

    /**
     * Sets the path for this request.
     */
    public HttpRequestBuilder path(String path) {
        this.path = requireNonNull(path, "path");
        return this;
    }

    /**
     * Sets the content for this request.
     */
    public HttpRequestBuilder content(MediaType contentType, CharSequence content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(content, "content");
        return content(contentType, HttpData.of(contentType.charset(StandardCharsets.UTF_8), content));
    }

    /**
     * Sets the content for this request.
     */
    public HttpRequestBuilder content(MediaType contentType, String content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(content, "content");
        return content(contentType, HttpData.of(contentType.charset(StandardCharsets.UTF_8), content));
    }

    /**
     * Sets the content for this request. The {@code content} is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @FormatMethod
    public HttpRequestBuilder content(MediaType contentType, @FormatString String format, Object... content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(format, "format");
        requireNonNull(content, "content");
        return content(contentType, HttpData.of(contentType.charset(StandardCharsets.UTF_8), format, content));
    }

    /**
     * Sets the content for this request. The {@code content} will be wrapped using
     * {@link HttpData#wrap(byte[])}, so any changes made to {@code content} will be reflected in the request.
     */
    public HttpRequestBuilder content(MediaType contentType, byte[] content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(content, "content");
        return content(contentType, HttpData.wrap(content));
    }

    /**
     * Sets the content for this request.
     */
    public HttpRequestBuilder content(MediaType contentType, HttpData content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(content, "content");
        requestHeadersBuilder.contentType(contentType);
        this.content = content;
        return this;
    }

    /**
     * Sets a header for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/")
     *            .header("authorization", "foo")
     *            .build();
     * }</pre>
     */
    public HttpRequestBuilder header(CharSequence name, Object value) {
        requestHeadersBuilder.setObject(requireNonNull(name, "name"), requireNonNull(value, "value"));
        return this;
    }

    /**
     * Sets multiple headers for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/")
     *            .headers(HttpHeaders.of("authorization", "foo", "bar", "baz"))
     *            .build();
     * }</pre>
     * @see HttpHeaders
     */
    public HttpRequestBuilder headers(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        requireNonNull(headers, "headers");
        requestHeadersBuilder.set(headers);
        return this;
    }

    /**
     * Sets HTTP trailers for this request.
     */
    public HttpRequestBuilder trailers(Iterable<? extends Entry<? extends CharSequence, String>> trailers) {
        requireNonNull(trailers, "trailers");
        httpTrailersBuilder.set(trailers);
        return this;
    }

    /**
     * Sets a path param for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/{foo}")
     *            .pathParam("foo", "bar")
     *            .build(); // GET `/bar`
     * }</pre>
     */
    public HttpRequestBuilder pathParam(String name, Object value) {
        pathParams.put(requireNonNull(name, "name"), requireNonNull(value, "value"));
        return this;
    }

    /**
     * Sets multiple path params for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/{foo}/:bar")
     *            .pathParams(Map.of("foo", "bar", "bar", "baz"))
     *            .build(); // GET `/bar/baz`
     * }</pre>
     */
    public HttpRequestBuilder pathParams(Map<String, ?> pathParams) {
        this.pathParams.putAll(requireNonNull(pathParams, "pathParams"));
        return this;
    }

    /**
     * Disables path parameters substitution. If path parameter is not disabled and a parameter's, specified
     * using {@code {}} or {@code :}, value is not found, an {@link IllegalStateException} is thrown.
     */
    public HttpRequestBuilder disablePathParams() {
        disablePathParams = true;
        return this;
    }

    /**
     * Sets a query param for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/endpoint")
     *            .queryParam("foo", "bar")
     *            .build(); // GET `/endpoint?foo=bar`
     * }</pre>
     */
    public HttpRequestBuilder queryParam(String name, Object value) {
        queryParamsBuilder.setObject(requireNonNull(name, "name"), requireNonNull(value, "value"));
        return this;
    }

    /**
     * Sets multiple query params for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/endpoint")
     *            .queryParams(QueryParams.of("from", "foo", "limit", 10))
     *            .build(); // GET `/endpoint?from=foo&limit=10`
     * }</pre>
     * @see QueryParams
     */
    public HttpRequestBuilder queryParams(Iterable<? extends Entry<? extends String, String>> queryParams) {
        requireNonNull(queryParams, "queryParams");
        queryParamsBuilder.set(queryParams);
        return this;
    }

    /**
     * Sets a cookie for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/")
     *            .cookie(Cookie.of("cookie", "foo"))
     *            .build();
     * }</pre>
     * @see Cookie
     */
    public HttpRequestBuilder cookie(Cookie cookie) {
        cookies.add(requireNonNull(cookie, "cookie"));
        return this;
    }

    /**
     * Sets multiple cookies for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/")
     *            .cookies(Cookies.of(Cookie.of("cookie1", "foo"),
     *                                Cookie.of("cookie2", "bar")))
     *            .build();
     * }</pre>
     * @see Cookies
     */
    public HttpRequestBuilder cookies(Iterable<? extends Cookie> cookies) {
        requireNonNull(cookies, "cookies").forEach(this.cookies::add);
        return this;
    }

    /**
     * Creates a new {@link HttpRequest}.
     */
    public HttpRequest build() {
        final RequestHeaders requestHeaders = requestHeaders();
        if (content == null || content.isEmpty()) {
            if (content != null) {
                content.close();
            }
            if (httpTrailersBuilder.isEmpty()) {
                return new EmptyFixedHttpRequest(requestHeaders);
            }
            return new OneElementFixedHttpRequest(requestHeaders, httpTrailersBuilder.build());
        }
        if (httpTrailersBuilder.isEmpty()) {
            return new OneElementFixedHttpRequest(requestHeaders, content);
        }
        return new TwoElementFixedHttpRequest(requestHeaders, content, httpTrailersBuilder.build());
    }

    private RequestHeaders requestHeaders() {
        requestHeadersBuilder.path(buildPath());
        if (!cookies.isEmpty()) {
            requestHeadersBuilder.set(COOKIE, Cookie.toCookieHeader(cookies));
        }
        if (content == null || content.isEmpty()) {
            requestHeadersBuilder.remove(CONTENT_LENGTH);
        } else {
            requestHeadersBuilder.setInt(CONTENT_LENGTH, content.length());
        }
        return requestHeadersBuilder.build();
    }

    private String buildPath() {
        checkState(path != null, "path must be set.");
        final StringBuilder pathBuilder = new StringBuilder(path);
        if (!disablePathParams) {
            int i = 0;
            while (i < pathBuilder.length()) {
                if (pathBuilder.charAt(i) == '{') {
                    int j = i + 1;
                    while (j < pathBuilder.length() && pathBuilder.charAt(j) != '}') {
                        j++;
                    }
                    if (j == pathBuilder.length()) {
                        break;
                    }
                    final String name = pathBuilder.substring(i + 1, j);
                    checkState(pathParams.containsKey(name), "param " + name + " does not have a value.");
                    final String value = pathParams.get(name).toString();
                    pathBuilder.replace(i, j + 1, value);
                    i += value.length() - 1;
                } else if (pathBuilder.charAt(i) == ':') {
                    int j = i + 1;
                    while (j < pathBuilder.length() && pathBuilder.charAt(j) != '/') {
                        j++;
                    }
                    final String name = pathBuilder.substring(i + 1, j);
                    checkState(pathParams.containsKey(name), "param " + name + " does not have a value.");
                    final String value = pathParams.get(name).toString();
                    pathBuilder.replace(i, j, value);
                    i += value.length();
                }
                i++;
            }
        }
        if (!queryParamsBuilder.isEmpty()) {
            pathBuilder.append('?').append(queryParamsBuilder.toQueryString());
        }
        return pathBuilder.toString();
    }
}
