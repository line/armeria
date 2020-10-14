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

import org.reactivestreams.Publisher;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.FixedHttpRequest.EmptyFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.OneElementFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.TwoElementFixedHttpRequest;

import io.netty.util.AsciiString;

/**
 * Builds a new {@link HttpRequest}.
 */
final class HttpRequestBuilder {

    private final RequestHeadersBuilder requestHeadersBuilder = RequestHeaders.builder();
    private final HttpHeadersBuilder httpTrailersBuilder = HttpHeaders.builder();
    private final QueryParamsBuilder queryParamsBuilder = QueryParams.builder();
    private final Map<String, Object> pathParams = new HashMap<>();
    private final List<Cookie> cookies = new ArrayList<>();
    @Nullable
    private HttpData content;
    @Nullable
    private Publisher<? extends HttpObject> publisher;
    @Nullable
    private String path;

    HttpRequestBuilder() {
    }

    HttpRequestBuilder(HttpMethod method, String path) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requestHeadersBuilder.method(method);
        this.path = path;
    }

    /**
     * Shortcut to create a new {@link HttpRequestBuilder} with GET method and path.
     */
    public HttpRequestBuilder get(String path) {
        method(HttpMethod.GET);
        path(path);
        return this;
    }

    /**
     * Shortcut to create a new {@link HttpRequestBuilder} with POST method and path.
     */
    public HttpRequestBuilder post(String path) {
        method(HttpMethod.POST);
        path(path);
        return this;
    }

    /**
     * Shortcut to create a new {@link HttpRequestBuilder} with PUT method and path.
     */
    public HttpRequestBuilder put(String path) {
        method(HttpMethod.PUT);
        path(path);
        return this;
    }

    /**
     * Shortcut to create a new {@link HttpRequestBuilder} with DELETE method and path.
     */
    public HttpRequestBuilder delete(String path) {
        method(HttpMethod.DELETE);
        path(path);
        return this;
    }

    /**
     * Sets the method for this request.
     *
     * @see HttpMethod
     */
    public HttpRequestBuilder method(HttpMethod method) {
        requireNonNull(method, "method");
        requestHeadersBuilder.method(method);
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
        requestHeadersBuilder.contentType(mediaType);
        this.content = HttpData.of(mediaType.charset(StandardCharsets.UTF_8), content);
        this.publisher = null;
        return this;
    }

    /**
     * Sets the content for this request.
     */
    public HttpRequestBuilder content(MediaType mediaType, String content) {
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        requestHeadersBuilder.contentType(mediaType);
        this.content = HttpData.of(mediaType.charset(StandardCharsets.UTF_8), content);
        this.publisher = null;
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
        requestHeadersBuilder.contentType(mediaType);
        this.content = HttpData.of(mediaType.charset(StandardCharsets.UTF_8), format, content);
        this.publisher = null;
        return this;
    }

    /**
     * Sets the content for this request.
     */
    public HttpRequestBuilder content(MediaType mediaType, byte[] content) {
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        requestHeadersBuilder.contentType(mediaType);
        this.content = HttpData.wrap(content);
        this.publisher = null;
        return this;
    }

    /**
     * Sets the content for this request.
     */
    public HttpRequestBuilder content(MediaType mediaType, HttpData content) {
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        requestHeadersBuilder.contentType(mediaType);
        this.content = content;
        this.publisher = null;
        return this;
    }

    /**
     * Sets the content for this request.
     */
    public HttpRequestBuilder content(MediaType mediaType, Publisher<HttpData> publisher) {
        requireNonNull(mediaType, "mediaType");
        requireNonNull(publisher, "publisher");
        requestHeadersBuilder.contentType(mediaType);
        this.publisher = publisher;
        this.content = null;
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
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        requestHeadersBuilder.setObject(name, value);
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
     *
     * @see HttpHeaders
     */
    public HttpRequestBuilder headers(HttpHeaders httpHeaders) {
        requireNonNull(httpHeaders, "httpHeaders");
        for (AsciiString name : httpHeaders.names()) {
            requestHeadersBuilder.set(name, requireNonNull(httpHeaders.get(name), "header"));
        }
        return this;
    }

    /**
     * Sets HTTP trailers for this request.
     */
    public HttpRequestBuilder trailers(HttpHeaders httpTrailers) {
        requireNonNull(httpTrailers, "httpTrailers");
        for (AsciiString name : httpTrailers.names()) {
            httpTrailersBuilder.set(name, requireNonNull(httpTrailers.get(name), "trailer"));
        }
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
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        pathParams.put(name, value);
        return this;
    }

    /**
     * Sets multiple path params for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/{foo}/{bar}")
     *            .pathParams(Map.of("foo", "bar", "bar", "baz"))
     *            .build(); // GET `/bar/baz`
     * }</pre>
     */
    public HttpRequestBuilder pathParams(Map<String, Object> pathParams) {
        requireNonNull(pathParams, "pathParams");
        this.pathParams.putAll(pathParams);
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
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        queryParamsBuilder.setObject(name, value);
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
     *
     * @see QueryParams
     */
    public HttpRequestBuilder queryParams(QueryParams queryParams) {
        requireNonNull(queryParams, "queryParams");
        for (String name : queryParams.names()) {
            queryParamsBuilder.set(name, requireNonNull(queryParams.get(name), "query"));
        }
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
     *
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
     * HttpRequest.builder()
     *            .get("/")
     *            .cookies(Cookies.of(Cookie.of("cookie1", "foo"),
     *                                Cookie.of("cookie2", "bar")))
     *            .build();
     * }</pre>
     *
     * @see Cookies
     */
    public HttpRequestBuilder cookies(Cookies cookies) {
        requireNonNull(cookies, "cookies");
        this.cookies.addAll(cookies);
        return this;
    }

    /**
     * Creates a new {@link HttpRequest}.
     */
    public HttpRequest build() {
        final RequestHeaders requestHeaders = requestHeaders();
        if (content == null || content.length() == 0) {
            if (content != null) {
                content.close();
            }
            if (publisher != null) {
                if (publisher instanceof HttpRequest) {
                    return ((HttpRequest) publisher).withHeaders(requestHeaders);
                }
                return new PublisherBasedHttpRequest(requestHeaders, publisher);
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
        requestHeadersBuilder.path(fullPath());
        if (!cookies.isEmpty()) {
            requestHeadersBuilder.set(COOKIE, Cookie.toCookieHeader(cookies));
        }
        if (content == null || content.length() == 0) {
            requestHeadersBuilder.remove(CONTENT_LENGTH);
        } else {
            requestHeadersBuilder.setInt(CONTENT_LENGTH, content.length());
        }
        return requestHeadersBuilder.build();
    }

    private String fullPath() {
        final StringBuilder pathBuilder = new StringBuilder(requireNonNull(this.path, "path"));
        if (!pathParams.isEmpty()) {
            int i = 0;
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
                        final String value = pathParams.get(name).toString();
                        pathBuilder.replace(i, j + 1, value);
                        i += value.length() - 1;
                    }
                } else if (pathBuilder.charAt(i) == ':') {
                    int j = i;
                    while (j < pathBuilder.length() && pathBuilder.charAt(j) != '/') {
                        j++;
                    }
                    final String name = pathBuilder.substring(i + 1, j);
                    if (pathParams.containsKey(name)) {
                        final String value = pathParams.get(name).toString();
                        pathBuilder.replace(i, j, value);
                        i += value.length();
                    }
                }
                i++;
            }
        }
        if (!queryParamsBuilder.isEmpty()) {
            pathBuilder.append("?").append(queryParamsBuilder.toQueryString());
        }
        return pathBuilder.toString();
    }
}
