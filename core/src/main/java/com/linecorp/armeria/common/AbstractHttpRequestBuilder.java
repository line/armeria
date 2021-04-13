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

import org.reactivestreams.Publisher;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.FixedHttpRequest.EmptyFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.OneElementFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.TwoElementFixedHttpRequest;
import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * Builds a new {@link HttpRequest}.
 */
public abstract class AbstractHttpRequestBuilder {

    private final RequestHeadersBuilder requestHeadersBuilder = RequestHeaders.builder();
    @Nullable
    private HttpHeadersBuilder httpTrailers;
    @Nullable
    private QueryParamsBuilder queryParams;
    @Nullable
    private Map<String, Object> pathParams;
    @Nullable
    private List<Cookie> cookies;
    @Nullable
    private HttpData content;
    @Nullable
    private Publisher<? extends HttpData> publisher;
    @Nullable
    private String path;
    private boolean disablePathParams;

    /**
     * Shortcut to set GET method and path.
     */
    public AbstractHttpRequestBuilder get(String path) {
        return method(HttpMethod.GET).path(path);
    }

    /**
     * Shortcut to set POST method and path.
     */
    public AbstractHttpRequestBuilder post(String path) {
        return method(HttpMethod.POST).path(path);
    }

    /**
     * Shortcut to set PUT method and path.
     */
    public AbstractHttpRequestBuilder put(String path) {
        return method(HttpMethod.PUT).path(path);
    }

    /**
     * Shortcut to set DELETE method and path.
     */
    public AbstractHttpRequestBuilder delete(String path) {
        return method(HttpMethod.DELETE).path(path);
    }

    /**
     * Shortcut to set PATCH method and path.
     */
    public AbstractHttpRequestBuilder patch(String path) {
        return method(HttpMethod.PATCH).path(path);
    }

    /**
     * Shortcut to set OPTIONS method and path.
     */
    public AbstractHttpRequestBuilder options(String path) {
        return method(HttpMethod.OPTIONS).path(path);
    }

    /**
     * Shortcut to set HEAD method and path.
     */
    public AbstractHttpRequestBuilder head(String path) {
        return method(HttpMethod.HEAD).path(path);
    }

    /**
     * Shortcut to set TRACE method and path.
     */
    public AbstractHttpRequestBuilder trace(String path) {
        return method(HttpMethod.TRACE).path(path);
    }

    /**
     * Sets the method for this request.
     * @see HttpMethod
     */
    public AbstractHttpRequestBuilder method(HttpMethod method) {
        requestHeadersBuilder.method(requireNonNull(method, "method"));
        return this;
    }

    /**
     * Sets the path for this request.
     */
    public AbstractHttpRequestBuilder path(String path) {
        this.path = requireNonNull(path, "path");
        return this;
    }

    /**
     * Sets the content for this request.
     */
    public AbstractHttpRequestBuilder content(MediaType contentType, CharSequence content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(content, "content");
        return content(contentType, HttpData.of(contentType.charset(StandardCharsets.UTF_8), content));
    }

    /**
     * Sets the content for this request.
     */
    public AbstractHttpRequestBuilder content(MediaType contentType, String content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(content, "content");
        return content(contentType, HttpData.of(contentType.charset(StandardCharsets.UTF_8), content));
    }

    /**
     * Sets the content for this request. The {@code content} is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @FormatMethod
    public AbstractHttpRequestBuilder content(MediaType contentType, @FormatString String format,
                                              Object... content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(format, "format");
        requireNonNull(content, "content");
        return content(contentType, HttpData.of(contentType.charset(StandardCharsets.UTF_8), format, content));
    }

    /**
     * Sets the content for this request. The {@code content} will be wrapped using
     * {@link HttpData#wrap(byte[])}, so any changes made to {@code content} will be reflected in the request.
     */
    public AbstractHttpRequestBuilder content(MediaType contentType, byte[] content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(content, "content");
        return content(contentType, HttpData.wrap(content));
    }

    /**
     * Sets the content for this request.
     */
    public AbstractHttpRequestBuilder content(MediaType contentType, HttpData content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(content, "content");
        requestHeadersBuilder.contentType(contentType);
        this.content = content;
        return this;
    }

    /**
     * Sets the {@link Publisher} for this request.
     */
    public AbstractHttpRequestBuilder content(MediaType contentType, Publisher<? extends HttpData> publisher) {
        requireNonNull(contentType, "contentType");
        requireNonNull(publisher, "publisher");
        checkState(content == null, "content has been set already");
        requestHeadersBuilder.contentType(contentType);
        this.publisher = publisher;
        return this;
    }

    /**
     * Adds a header for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/")
     *            .header("authorization", "foo")
     *            .build();
     * }</pre>
     */
    public AbstractHttpRequestBuilder header(CharSequence name, Object value) {
        requestHeadersBuilder.setObject(requireNonNull(name, "name"), requireNonNull(value, "value"));
        return this;
    }

    /**
     * Adds multiple headers for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/")
     *            .headers(HttpHeaders.of("authorization", "foo", "bar", "baz"))
     *            .build();
     * }</pre>
     * @see HttpHeaders
     */
    public AbstractHttpRequestBuilder headers(
            Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        requireNonNull(headers, "headers");
        requestHeadersBuilder.set(headers);
        return this;
    }

    /**
     * Sets HTTP trailers for this request.
     */
    public AbstractHttpRequestBuilder trailers(
            Iterable<? extends Entry<? extends CharSequence, String>> trailers) {
        requireNonNull(trailers, "trailers");
        if (httpTrailers == null) {
            httpTrailers = HttpHeaders.builder();
        }
        httpTrailers.set(trailers);
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
    public AbstractHttpRequestBuilder pathParam(String name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        if (pathParams == null) {
            pathParams = new HashMap<>();
        }
        pathParams.put(name, value);
        return this;
    }

    /**
     * Sets multiple path params for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/{foo}/:bar")
     *            .pathParams(Map.of("foo", 1, "bar", 2))
     *            .build(); // GET `/1/2`
     * }</pre>
     */
    public AbstractHttpRequestBuilder pathParams(Map<String, ?> pathParams) {
        requireNonNull(pathParams, "pathParams");
        if (this.pathParams == null) {
            this.pathParams = new HashMap<>();
        }
        this.pathParams.putAll(pathParams);
        return this;
    }

    /**
     * Disables path parameters substitution. If path parameter is not disabled and a parameter's, specified
     * using {@code {}} or {@code :}, value is not found, an {@link IllegalStateException} is thrown.
     */
    public AbstractHttpRequestBuilder disablePathParams() {
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
    public AbstractHttpRequestBuilder queryParam(String name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        if (queryParams == null) {
            queryParams = QueryParams.builder();
        }
        queryParams.setObject(name, value);
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
    public AbstractHttpRequestBuilder queryParams(
            Iterable<? extends Entry<? extends String, String>> queryParams) {
        requireNonNull(queryParams, "queryParams");
        if (this.queryParams == null) {
            this.queryParams = QueryParams.builder();
        }
        this.queryParams.set(queryParams);
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
    public AbstractHttpRequestBuilder cookie(Cookie cookie) {
        requireNonNull(cookie, "cookie");
        if (cookies == null) {
            cookies = new ArrayList<>();
        }
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
     * @see Cookies
     */
    public AbstractHttpRequestBuilder cookies(Iterable<? extends Cookie> cookies) {
        requireNonNull(cookies, "cookies");
        if (this.cookies == null) {
            this.cookies = new ArrayList<>();
        }
        cookies.forEach(this.cookies::add);
        return this;
    }

    /**
     * Creates a new {@link HttpRequest}.
     */
    protected final HttpRequest buildRequest() {
        final RequestHeaders requestHeaders = requestHeaders();
        if (publisher != null) {
            if (httpTrailers == null) {
                return HttpRequest.of(requestHeaders, publisher);
            } else {
                return HttpRequest.of(requestHeaders,
                                      StreamMessage.concat(publisher, StreamMessage.of(httpTrailers.build())));
            }
        }
        if (content == null || content.isEmpty()) {
            if (content != null) {
                content.close();
            }
            if (httpTrailers == null) {
                return new EmptyFixedHttpRequest(requestHeaders);
            }
            return new OneElementFixedHttpRequest(requestHeaders, httpTrailers.build());
        }
        if (httpTrailers == null) {
            return new OneElementFixedHttpRequest(requestHeaders, content);
        }
        return new TwoElementFixedHttpRequest(requestHeaders, content, httpTrailers.build());
    }

    private RequestHeaders requestHeaders() {
        requestHeadersBuilder.path(buildPath());
        if (cookies != null) {
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
        if (disablePathParams && queryParams == null) {
            return path;
        }

        // As the path could contain path params such as '{foo-}' or ':123' which could violate a URI syntax
        // defined by RFC2396, we can not use 'URI.create()' here.
        int pathStart = -1;
        int pathEnd = -1;
        int fragmentStart = -1;
        final int pathLength = path.length();
        boolean hasScheme = false;
        boolean hasQueryString = false;
        for (int i = 0; i < pathLength; ) {
            final char current = path.charAt(i);
            if (pathStart == -1 && current == ':') {
                if (pathLength > i + 2 && path.charAt(i + 1) == '/' && path.charAt(i + 2) == '/') {
                    // a scheme component
                    i += 3;
                    hasScheme = true;
                    continue;
                }

                if (hasScheme) {
                    // a port component
                    i++;
                    continue;
                }
            }
            if (pathStart == -1 && current == '/') {
                // start a path component
                pathStart = i;
            }

            if (!hasQueryString && current == '?') {
                hasQueryString = true;
                if (pathEnd == -1) {
                    pathEnd = i;
                }
            }

            if (fragmentStart == -1 && current == '#') {
                fragmentStart = i;
                if (pathEnd == -1) {
                    pathEnd = i;
                }
            }
            i++;
        }

        if (!hasScheme) {
            // path only. e.g, /foo/bar?abc
            pathStart = 0;
        } else {
            if (pathStart == -1) {
                // no path. e.g, https://armeria.dev
                pathStart = pathLength;
            }
        }

        if (pathEnd == -1) {
            pathEnd = pathLength;
        }

        final StringBuilder pathBuilder = new StringBuilder(path.substring(pathStart, pathEnd));
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
                    if (i + 1 == j) {
                        // skip an empty braces '{}'
                    } else {
                        final String name = pathBuilder.substring(i + 1, j);
                        checkState(pathParams != null && pathParams.containsKey(name),
                                   "param " + name + " does not have a value.");
                        final String value = pathParams.get(name).toString();
                        pathBuilder.replace(i, j + 1, value);
                        i += value.length() - 1;
                    }
                } else if (pathBuilder.charAt(i) == ':') {
                    int j = i + 1;
                    while (j < pathBuilder.length() && pathBuilder.charAt(j) != '/') {
                        j++;
                    }
                    if (i + 1 == j) {
                        // skip a ':' along with '/' . e.g, "http://", "/foo/:/"
                    } else {
                        final String name = pathBuilder.substring(i + 1, j);
                        checkState(pathParams != null && pathParams.containsKey(name),
                                   "param " + name + " does not have a value.");
                        final String value = pathParams.get(name).toString();
                        pathBuilder.replace(i, j, value);
                        i += value.length();
                    }
                }
                i++;
            }
        }

        final StringBuilder newPathBuilder;
        if (pathStart == 0) {
            newPathBuilder = new StringBuilder();
        } else {
            newPathBuilder = new StringBuilder(path.substring(0, pathStart));
        }
        newPathBuilder.append(pathBuilder);

        if (pathEnd < pathLength) {
            if (fragmentStart == -1) {
                newPathBuilder.append(path, pathEnd, pathLength);
            } else {
                // Remove fragment from the rest
                newPathBuilder.append(path, pathEnd, fragmentStart);
            }
        }

        if (queryParams != null) {
            if (hasQueryString) {
                newPathBuilder.append('&');
            } else {
                newPathBuilder.append('?');
            }
            newPathBuilder.append(queryParams.toQueryString());
        }

        if (fragmentStart > -1) {
            newPathBuilder.append(path, fragmentStart, pathLength);
        }

        return newPathBuilder.toString();
    }
}
