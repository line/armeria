/*
 * Copyright 2016 LINE Corporation
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
import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Formatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * A streamed HTTP/2 {@link Request}.
 *
 * <p>Note: The initial {@link HttpHeaders} is not signaled to {@link Subscriber}s. It is readily available
 * via {@link #headers()}.
 */
public interface HttpRequest extends Request, StreamMessage<HttpObject> {

    // Note: Ensure we provide the same set of `of()` methods with the `of()` methods of
    //       AggregatedHttpMessage for consistency.

    /**
     * Creates a new HTTP request with empty content and closes the stream.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     */
    static HttpRequest of(HttpMethod method, String path) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        return of(HttpHeaders.of(method, path));
    }

    /**
     * Creates a new HTTP request and closes the stream.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     */
    static HttpRequest of(HttpMethod method, String path, MediaType mediaType, String content) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(content, "content");
        requireNonNull(mediaType, "mediaType");
        return of(method, path,
                  mediaType, content.getBytes(mediaType.charset().orElse(StandardCharsets.UTF_8)));
    }

    /**
     * Creates a new HTTP request and closes the stream. The content of the request is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param format {@linkplain Formatter the format string} of the request content
     * @param args the arguments referenced by the format specifiers in the format string
     */
    static HttpRequest of(HttpMethod method, String path, MediaType mediaType, String format, Object... args) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        return of(method,
                  path,
                  mediaType,
                  String.format(Locale.ENGLISH, format, args).getBytes(
                          mediaType.charset().orElse(StandardCharsets.UTF_8)));
    }

    /**
     * Creates a new HTTP request and closes the stream.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     */
    static HttpRequest of(HttpMethod method, String path, MediaType mediaType, byte[] content) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(method, path, mediaType, HttpData.of(content));
    }

    /**
     * Creates a new HTTP request and closes the stream.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     * @param offset the start offset of {@code content}
     * @param length the length of {@code content}
     */
    static HttpRequest of(
            HttpMethod method, String path, MediaType mediaType, byte[] content, int offset, int length) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(method, path, mediaType, HttpData.of(content, offset, length));
    }

    /**
     * Creates a new HTTP request and closes the stream.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     */
    static HttpRequest of(HttpMethod method, String path, MediaType mediaType, HttpData content) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(method, path, mediaType, content, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new HTTP request and closes the stream.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     * @param trailingHeaders the trailing HTTP headers
     */
    static HttpRequest of(HttpMethod method, String path, MediaType mediaType, HttpData content,
                          HttpHeaders trailingHeaders) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        requireNonNull(trailingHeaders, "trailingHeaders");

        return of(HttpHeaders.of(method, path).setObject(CONTENT_TYPE, mediaType), content, trailingHeaders);
    }

    /**
     * Creates a new {@link HttpRequest} with empty content and closes the stream.
     */
    static HttpRequest of(HttpHeaders headers) {
        requireNonNull(headers, "headers");
        return of(headers, HttpData.EMPTY_DATA);
    }

    /**
     * Creates a new {@link HttpRequest} and closes the stream.
     */
    static HttpRequest of(HttpHeaders headers, HttpData content) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        return of(headers, content, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new {@link HttpRequest} and closes the stream.
     */
    static HttpRequest of(HttpHeaders headers, HttpData content, HttpHeaders trailingHeaders) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        requireNonNull(trailingHeaders, "trailingHeaders");

        if (content.isEmpty()) {
            headers.remove(CONTENT_LENGTH);
        } else {
            headers.setInt(CONTENT_LENGTH, content.length());
        }

        final DefaultHttpRequest req = new DefaultHttpRequest(headers);
        if (!content.isEmpty()) {
            req.write(content);
        }
        if (!trailingHeaders.isEmpty()) {
            req.write(trailingHeaders);
        }
        req.close();
        return req;
    }

    /**
     * Creates a new instance from an existing {@link HttpHeaders} and {@link Publisher}.
     */
    static HttpRequest of(HttpHeaders headers, Publisher<? extends HttpObject> publisher) {
        requireNonNull(publisher, "publisher");
        return new PublisherBasedHttpRequest(headers, true, publisher);
    }

    /**
     * Returns the initial HTTP/2 headers of this request.
     */
    HttpHeaders headers();

    /**
     * Returns whether to keep the connection alive after this request is handled.
     */
    boolean isKeepAlive();

    /**
     * Returns the scheme of this request. This method is a shortcut of {@code headers().scheme()}.
     */
    default String scheme() {
        return headers().scheme();
    }

    /**
     * Sets the scheme of this request. This method is a shortcut of {@code headers().scheme(...)}.
     *
     * @return {@code this}
     */
    default HttpRequest scheme(String scheme) {
        headers().scheme(scheme);
        return this;
    }

    /**
     * Returns the method of this request. This method is a shortcut of {@code headers().method()}.
     */
    default HttpMethod method() {
        return headers().method();
    }

    /**
     * Sets the method of this request. This method is a shortcut of {@code headers().method(...)}.
     *
     * @return {@code this}
     */
    default HttpRequest method(HttpMethod method) {
        headers().method(method);
        return this;
    }

    /**
     * Returns the path of this request. This method is a shortcut of {@code headers().path()}.
     */
    default String path() {
        return headers().path();
    }

    /**
     * Sets the path of this request. This method is a shortcut of {@code headers().path(...)}.
     *
     * @return {@code this}
     */
    default HttpRequest path(String path) {
        headers().path(path);
        return this;
    }

    /**
     * Returns the authority of this request. This method is a shortcut of {@code headers().authority()}.
     */
    default String authority() {
        return headers().authority();
    }

    /**
     * Sets the authority of this request. This method is a shortcut of {@code headers().authority(...)}.
     *
     * @return {@code this}
     */
    default HttpRequest authority(String authority) {
        headers().authority(authority);
        return this;
    }

    /**
     * Aggregates this request. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the request is received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate() {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpRequestAggregator aggregator = new HttpRequestAggregator(this, future);
        completionFuture().whenComplete(aggregator);
        subscribe(aggregator);
        return future;
    }

    /**
     * Aggregates this request. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the request is received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate(Executor executor) {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpRequestAggregator aggregator = new HttpRequestAggregator(this, future);
        completionFuture().whenCompleteAsync(aggregator, executor);
        subscribe(aggregator, executor);
        return future;
    }
}
