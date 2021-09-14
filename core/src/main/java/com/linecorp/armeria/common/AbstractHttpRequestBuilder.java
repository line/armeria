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

import static com.google.common.base.Preconditions.checkArgument;
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

import org.reactivestreams.Publisher;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.FixedHttpRequest.EmptyFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.OneElementFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.TwoElementFixedHttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

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
    private Map<String, String> pathParams;
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
     *
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
        requireNonNull(path, "path");
        checkArgument(!path.isEmpty(), "path is empty.");
        this.path = path;
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
     *
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
        checkArgument(!name.isEmpty(), "name is empty.");
        if (pathParams == null) {
            pathParams = new HashMap<>();
        }
        pathParams.put(name, value.toString());
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
        if (pathParams.isEmpty()) {
            return this;
        }

        checkArgument(!pathParams.containsKey(""),
                      "pathParams contains an entry with an empty name: %s", pathParams);

        if (this.pathParams == null) {
            this.pathParams = new HashMap<>();
        }

        pathParams.forEach((key, value) -> this.pathParams.put(key, value.toString()));

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
     *
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
     *
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
     *
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
            requestHeadersBuilder.contentLength(content.length());
        }
        return requestHeadersBuilder.build();
    }

    private String buildPath() {
        checkState(path != null, "path must be set.");

        if (!disablePathParams) {
            // Path parameter substitution is enabled. Look for : or { first.
            final int pathLen = path.length();
            int i = 0;
            boolean hasPathParams = false;
            boolean hasQueryInPath = false;

            loop:
            while (i < pathLen) {
                switch (path.charAt(i)) {
                    case ':':
                        if (i + 1 < pathLen && path.charAt(i + 1) == '/') {
                            if (i + 2 < pathLen && path.charAt(i + 2) == '/') {
                                // Found '://', i.e. path is an absolute URI.
                                final int pathStart = path.indexOf('/', i + 3);
                                if (pathStart < 0) {
                                    // The URI doesn't have a path, e.g. http://example.com:8080
                                    i = pathLen;
                                    break loop;
                                } else {
                                    i = pathStart + 1; // +1 to skip the first '/' in the path
                                    continue;
                                }
                            } else {
                                // Found ':/' - Skip.
                                i += 2;
                                continue;
                            }
                        } else {
                            hasPathParams = true;
                            break loop;
                        }
                    case '{':
                        if (i + 1 < pathLen && path.charAt(i + 1) == '}') {
                            // Found '{}' - Skip.
                            i += 2;
                            continue;
                        } else {
                            hasPathParams = true;
                            break loop;
                        }
                    case '?':
                        hasQueryInPath = true;
                        break loop;
                }
                i++;
            }

            if (hasPathParams) {
                // Replace path parameters.
                try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
                    final StringBuilder buf = tempThreadLocals.stringBuilder();
                    buf.append(path, 0, i);

                    loop:
                    while (i < pathLen) {
                        final char ch = path.charAt(i);
                        switch (ch) {
                            case '{': {
                                int j = i + 1;
                                // Find the matching '}'
                                while (j < pathLen && path.charAt(j) != '}') {
                                    j++;
                                }

                                if (j >= pathLen) {
                                    // Found no matching '}'
                                    buf.append(path, i, pathLen);
                                    break loop;
                                }

                                if (j > i + 1) {
                                    final String name = path.substring(i + 1, j);
                                    checkState(pathParams != null && pathParams.containsKey(name),
                                               "param '%s' does not have a value.", name);
                                    buf.append(pathParams.get(name));
                                    j++; // Skip '}'
                                } else {
                                    // Found '{}'
                                    j++; // Skip '}'
                                    buf.append('{').append('}');
                                }
                                i = j;
                                break;
                            }
                            case ':': {
                                int j = i + 1;
                                while (j < pathLen && path.charAt(j) != '/' && path.charAt(j) != '?') {
                                    j++;
                                }
                                if (j > i + 1) {
                                    final String name = path.substring(i + 1, j);
                                    checkState(pathParams != null && pathParams.containsKey(name),
                                               "param '%s' does not have a value.", name);
                                    buf.append(pathParams.get(name));
                                } else {
                                    // Found ':' without name.
                                    buf.append(':');
                                }
                                i = j;
                                break;
                            }
                            case '?': {
                                hasQueryInPath = true;
                                buf.append(path, i, pathLen);
                                break loop;
                            }
                            default:
                                buf.append(ch);
                                i++;
                        }
                    }

                    if (hasQueryInPath) {
                        if (queryParams != null) {
                            buf.append('&');
                            queryParams.appendQueryString(buf);
                        }
                    } else {
                        if (queryParams != null) {
                            buf.append('?');
                            queryParams.appendQueryString(buf);
                        }
                    }

                    return buf.toString();
                }
            } else {
                // path doesn't contain a path parameter.
                if (queryParams != null) {
                    return buildPathWithoutPathParams(path, queryParams, hasQueryInPath);
                }
            }
        } else {
            // Path parameter substitution is disabled.
            if (queryParams != null) {
                return buildPathWithoutPathParams(path, queryParams, path.indexOf('?') >= 0);
            }
        }

        // No path/query parameters to substitute/encode
        return path;
    }

    private static String buildPathWithoutPathParams(
            String path, QueryParamsBuilder queryParams, boolean hasQueryInPath) {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            buf.append(path).append(hasQueryInPath ? '&' : '?');
            queryParams.appendQueryString(buf);
            return buf.toString();
        }
    }
}
