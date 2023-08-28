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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_LENGTH;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.reactivestreams.Publisher;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * Builds a new {@link HttpRequest}.
 */
public abstract class AbstractHttpRequestBuilder
        extends AbstractHttpMessageBuilder implements HttpRequestSetters {

    private final RequestHeadersBuilder requestHeadersBuilder = RequestHeaders.builder();
    @Nullable
    private QueryParamsBuilder queryParams;
    @Nullable
    private Map<String, String> pathParams;
    @Nullable
    private String path;
    private boolean disablePathParams;

    @Override
    public AbstractHttpRequestBuilder get(String path) {
        return method(HttpMethod.GET).path(path);
    }

    @Override
    public AbstractHttpRequestBuilder post(String path) {
        return method(HttpMethod.POST).path(path);
    }

    @Override
    public AbstractHttpRequestBuilder put(String path) {
        return method(HttpMethod.PUT).path(path);
    }

    @Override
    public AbstractHttpRequestBuilder delete(String path) {
        return method(HttpMethod.DELETE).path(path);
    }

    @Override
    public AbstractHttpRequestBuilder patch(String path) {
        return method(HttpMethod.PATCH).path(path);
    }

    @Override
    public AbstractHttpRequestBuilder options(String path) {
        return method(HttpMethod.OPTIONS).path(path);
    }

    @Override
    public AbstractHttpRequestBuilder head(String path) {
        return method(HttpMethod.HEAD).path(path);
    }

    @Override
    public AbstractHttpRequestBuilder trace(String path) {
        return method(HttpMethod.TRACE).path(path);
    }

    @Override
    public AbstractHttpRequestBuilder method(HttpMethod method) {
        requestHeadersBuilder.method(requireNonNull(method, "method"));
        return this;
    }

    @Override
    public AbstractHttpRequestBuilder path(String path) {
        requireNonNull(path, "path");
        this.path = path;
        return this;
    }

    @Override
    public AbstractHttpRequestBuilder content(String content) {
        return (AbstractHttpRequestBuilder) super.content(content);
    }

    @Override
    public AbstractHttpRequestBuilder content(MediaType contentType, CharSequence content) {
        return (AbstractHttpRequestBuilder) super.content(contentType, content);
    }

    @Override
    public AbstractHttpRequestBuilder content(MediaType contentType, String content) {
        return (AbstractHttpRequestBuilder) super.content(contentType, content);
    }

    @Override
    @FormatMethod
    public AbstractHttpRequestBuilder content(@FormatString String format, Object... content) {
        return (AbstractHttpRequestBuilder) super.content(format, content);
    }

    @Override
    @FormatMethod
    public AbstractHttpRequestBuilder content(MediaType contentType, @FormatString String format,
                                              Object... content) {
        return (AbstractHttpRequestBuilder) super.content(contentType, format, content);
    }

    @Override
    public AbstractHttpRequestBuilder content(MediaType contentType, byte[] content) {
        return (AbstractHttpRequestBuilder) super.content(contentType, content);
    }

    @Override
    public AbstractHttpRequestBuilder content(MediaType contentType, HttpData content) {
        return (AbstractHttpRequestBuilder) super.content(contentType, content);
    }

    @Override
    public AbstractHttpRequestBuilder content(Publisher<? extends HttpData> content) {
        return (AbstractHttpRequestBuilder) super.content(content);
    }

    @Override
    public AbstractHttpRequestBuilder content(MediaType contentType, Publisher<? extends HttpData> content) {
        return (AbstractHttpRequestBuilder) super.content(contentType, content);
    }

    @Override
    public AbstractHttpRequestBuilder contentJson(Object content) {
        return (AbstractHttpRequestBuilder) super.contentJson(content);
    }

    @Override
    public AbstractHttpRequestBuilder header(CharSequence name, Object value) {
        return (AbstractHttpRequestBuilder) super.header(name, value);
    }

    @Override
    public AbstractHttpRequestBuilder headers(
            Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        return (AbstractHttpRequestBuilder) super.headers(headers);
    }

    @Override
    public AbstractHttpRequestBuilder trailers(
            Iterable<? extends Entry<? extends CharSequence, String>> trailers) {
        return (AbstractHttpRequestBuilder) super.trailers(trailers);
    }

    @Override
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

    @Override
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

    @Override
    public AbstractHttpRequestBuilder disablePathParams() {
        disablePathParams = true;
        return this;
    }

    @Override
    public AbstractHttpRequestBuilder queryParam(String name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        if (queryParams == null) {
            queryParams = QueryParams.builder();
        }
        queryParams.addObject(name, value);
        return this;
    }

    @Override
    public AbstractHttpRequestBuilder queryParams(
            Iterable<? extends Entry<? extends String, String>> queryParams) {
        requireNonNull(queryParams, "queryParams");
        if (this.queryParams == null) {
            this.queryParams = QueryParams.builder();
        }
        this.queryParams.add(queryParams);
        return this;
    }

    @Override
    public AbstractHttpRequestBuilder cookie(Cookie cookie) {
        requireNonNull(cookie, "cookie");
        requestHeadersBuilder.cookie(cookie);
        return this;
    }

    @Override
    public AbstractHttpRequestBuilder cookies(Iterable<? extends Cookie> cookies) {
        requireNonNull(cookies, "cookies");
        requestHeadersBuilder.cookies(cookies);
        return this;
    }

    /**
     * Creates a new {@link HttpRequest}.
     */
    protected final HttpRequest buildRequest() {
        final RequestHeaders requestHeaders = requestHeaders();
        final Publisher<? extends HttpData> publisher = publisher();
        final HttpHeadersBuilder httpTrailersBuilder = httpTrailers();
        if (publisher != null) {
            if (httpTrailersBuilder == null) {
                return HttpRequest.of(requestHeaders, publisher);
            } else {
                return HttpRequest.of(requestHeaders,
                                      StreamMessage.concat(publisher,
                                                           StreamMessage.of(httpTrailersBuilder.build())));
            }
        }
        final HttpData content = firstNonNull(content(), HttpData.empty());
        final HttpHeaders httpTrailers;
        if (httpTrailersBuilder != null) {
            httpTrailers = httpTrailersBuilder.build();
        } else {
            httpTrailers = HttpHeaders.of();
        }
        return HttpRequest.of(requestHeaders, content, httpTrailers);
    }

    @Override
    final HttpHeadersBuilder headersBuilder() {
        return requestHeadersBuilder;
    }

    private RequestHeaders requestHeaders() {
        requestHeadersBuilder.path(buildPath());
        @SuppressWarnings("resource")
        final HttpData content = content();
        if (content == null || content.isEmpty()) {
            //noinspection ReactiveStreamsUnusedPublisher
            if (publisher() == null) {
                requestHeadersBuilder.remove(CONTENT_LENGTH);
            }
        } else {
            requestHeadersBuilder.contentLength(content.length());
        }
        return requestHeadersBuilder.build();
    }

    private String buildPath() {
        final String headerPath = requestHeadersBuilder.get(HttpHeaderNames.PATH);
        checkState(path != null || headerPath != null, "path must be set.");
        final String path = firstNonNull(this.path, headerPath);

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
