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
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Formatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.FixedHttpRequest.EmptyFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.OneElementFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.RegularFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.TwoElementFixedHttpRequest;
import com.linecorp.armeria.common.stream.StreamMessage;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * A streamed HTTP/2 {@link Request}.
 *
 * <p>Note: The initial {@link RequestHeaders} is not signaled to {@link Subscriber}s. It is readily available
 * via {@link #headers()}.
 */
public interface HttpRequest extends Request, StreamMessage<HttpObject> {

    // Note: Ensure we provide the same set of `of()` methods with the `of()` methods of
    //       AggregatedHttpMessage for consistency.

    /**
     * Creates a new HTTP request that can be used to stream an arbitrary number of {@link HttpObject}
     * with the initial {@link RequestHeaders} of the specified {@link HttpMethod} and {@code path}.
     */
    static HttpRequestWriter streaming(HttpMethod method, String path) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        return streaming(RequestHeaders.of(method, path));
    }

    /**
     * Creates a new HTTP request that can be used to stream an arbitrary number of {@link HttpObject}
     * with the specified initial {@link RequestHeaders}.
     */
    static HttpRequestWriter streaming(RequestHeaders headers) {
        requireNonNull(headers, "headers");
        return new DefaultHttpRequest(headers);
    }

    /**
     * Creates a new HTTP request with empty content and closes the stream.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     */
    static HttpRequest of(HttpMethod method, String path) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        return of(RequestHeaders.of(method, path));
    }

    /**
     * Creates a new HTTP request and closes the stream.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     */
    static HttpRequest of(HttpMethod method, String path, MediaType mediaType, CharSequence content) {
        if (content instanceof String) {
            return of(method, path, mediaType, (String) content);
        }

        requireNonNull(content, "content");
        requireNonNull(mediaType, "mediaType");
        return of(method, path, mediaType,
                  HttpData.of(mediaType.charset().orElse(StandardCharsets.UTF_8), content));
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
        requireNonNull(content, "content");
        requireNonNull(mediaType, "mediaType");
        return of(method, path, mediaType,
                  HttpData.of(mediaType.charset().orElse(StandardCharsets.UTF_8), content));
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
        return of(method, path, mediaType,
                  HttpData.of(mediaType.charset().orElse(StandardCharsets.UTF_8), format, args));
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
        return of(method, path, mediaType, content, HttpHeaders.of());
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
        return of(RequestHeaders.builder(method, path)
                                .contentType(mediaType)
                                .build(),
                  content, trailingHeaders);
    }

    /**
     * Creates a new {@link HttpRequest} with empty content and closes the stream.
     */
    static HttpRequest of(RequestHeaders headers) {
        return of(headers, HttpData.EMPTY_DATA);
    }

    /**
     * Creates a new {@link HttpRequest} and closes the stream.
     */
    static HttpRequest of(RequestHeaders headers, HttpData content) {
        return of(headers, content, HttpHeaders.of());
    }

    /**
     * Creates a new {@link HttpRequest} and closes the stream.
     *
     * @throws IllegalStateException if the headers are malformed.
     */
    static HttpRequest of(RequestHeaders headers, HttpData content, HttpHeaders trailingHeaders) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        requireNonNull(trailingHeaders, "trailingHeaders");

        if (content.isEmpty()) {
            final RequestHeadersBuilder builder = headers.toBuilder();
            builder.remove(CONTENT_LENGTH);
            headers = builder.build();
        } else {
            headers = headers.toBuilder()
                             .setInt(CONTENT_LENGTH, content.length())
                             .build();
        }

        if (!content.isEmpty()) {
            if (trailingHeaders.isEmpty()) {
                return new OneElementFixedHttpRequest(headers, content);
            } else {
                return new TwoElementFixedHttpRequest(headers, content, trailingHeaders);
            }
        } else if (!trailingHeaders.isEmpty()) {
            return new OneElementFixedHttpRequest(headers, trailingHeaders);
        } else {
            return new EmptyFixedHttpRequest(headers);
        }
    }

    /**
     * Creates a new {@link HttpRequest} that publishes the given {@link HttpObject}s and closes the stream.
     * {@code objs} must not contain {@link RequestHeaders}.
     */
    static HttpRequest of(RequestHeaders headers, HttpObject... objs) {
        if (Arrays.stream(objs).anyMatch(obj -> obj instanceof HttpHeaders)) {
            throw new IllegalArgumentException("objs contains Headers, which is not allowed.");
        }
        switch (objs.length) {
            case 0:
                return new EmptyFixedHttpRequest(headers);
            case 1:
                return new OneElementFixedHttpRequest(headers, objs[0]);
            case 2:
                return new TwoElementFixedHttpRequest(headers, objs[0], objs[1]);
            default:
                return new RegularFixedHttpRequest(headers, objs);
        }
    }

    /**
     * Converts the {@link AggregatedHttpMessage} into a new {@link HttpRequest} and closes the stream.
     */
    static HttpRequest of(AggregatedHttpMessage message) {
        return of(RequestHeaders.of(message.headers()), message.content(), message.trailingHeaders());
    }

    /**
     * Creates a new instance from an existing {@link RequestHeaders} and {@link Publisher}.
     */
    static HttpRequest of(RequestHeaders headers, Publisher<? extends HttpObject> publisher) {
        requireNonNull(publisher, "publisher");
        return new PublisherBasedHttpRequest(headers, publisher);
    }

    /**
     * Creates a new instance from an existing {@link HttpRequest} replacing its {@link RequestHeaders}
     * with the specified {@code newHeaders}. Make sure to update {@link RequestContext#request()} with
     * {@link RequestContext#updateRequest(Request)} if you are intercepting an {@link HttpRequest}
     * in a decorator. For example:
     * <pre>{@code
     * > public class MyService extends SimpleDecoratingService<HttpRequest, HttpResponse> {
     * >     @Override
     * >     public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
     * >         // Create a new request with an additional header.
     * >         final HttpRequest newReq = HttpRequest.of(
     * >                 req, req.headers().toBuilder()
     * >                         .set("x-custom-header", "value")
     * >                         .build());
     * >
     * >         // Update the ctx.request.
     * >         ctx.updateRequest(newReq);
     * >
     * >         // Delegate the new request with the updated context.
     * >         return delegate().serve(ctx, newReq);
     * >     }
     * > }
     * }</pre>
     */
    static HttpRequest of(HttpRequest request, RequestHeaders newHeaders) {
        requireNonNull(request, "request");
        requireNonNull(newHeaders, "newHeaders");
        if (request.headers() == newHeaders) {
            // Just check the reference only to avoid heavy comparison.
            return request;
        }

        if (request instanceof HeaderOverridingHttpRequest) {
            request = ((HeaderOverridingHttpRequest) request).unwrap();
        }

        if (request.headers() == newHeaders) {
            return request;
        }

        return new HeaderOverridingHttpRequest(request, newHeaders);
    }

    /**
     * Returns the initial HTTP/2 headers of this request.
     */
    RequestHeaders headers();

    /**
     * Returns the scheme of this request. This method is a shortcut of {@code headers().scheme()}.
     */
    @Nullable
    default String scheme() {
        return headers().scheme();
    }

    /**
     * Returns the method of this request. This method is a shortcut of {@code headers().method()}.
     */
    default HttpMethod method() {
        return headers().method();
    }

    /**
     * Returns the path of this request. This method is a shortcut of {@code headers().path()}.
     */
    default String path() {
        return headers().path();
    }

    /**
     * Returns the authority of this request. This method is a shortcut of {@code headers().authority()}.
     */
    @Nullable
    default String authority() {
        return headers().authority();
    }

    /**
     * Returns the value of the {@code 'content-type'} header.
     * @return the valid header value if present. {@code null} otherwise.
     */
    @Nullable
    default MediaType contentType() {
        return headers().contentType();
    }

    /**
     * Aggregates this request. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the request is received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate() {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpRequestAggregator aggregator = new HttpRequestAggregator(this, future, null);
        completionFuture().handle(aggregator);
        subscribe(aggregator);
        return future;
    }

    /**
     * Aggregates this request. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the request is received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate(EventExecutor executor) {
        requireNonNull(executor, "executor");
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpRequestAggregator aggregator = new HttpRequestAggregator(this, future, null);
        completionFuture().handleAsync(aggregator, executor);
        subscribe(aggregator, executor);
        return future;
    }

    /**
     * Aggregates this request. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the request is received fully. {@link AggregatedHttpMessage#content()} will
     * return a pooled object, and the caller must ensure to release it. If you don't know what this means,
     * use {@link #aggregate()}.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregateWithPooledObjects(ByteBufAllocator alloc) {
        requireNonNull(alloc, "alloc");
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpRequestAggregator aggregator = new HttpRequestAggregator(this, future, alloc);
        completionFuture().handle(aggregator);
        subscribe(aggregator, true);
        return future;
    }

    /**
     * Aggregates this request. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the request is received fully. {@link AggregatedHttpMessage#content()} will
     * return a pooled object, and the caller must ensure to release it. If you don't know what this means,
     * use {@link #aggregate()}.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregateWithPooledObjects(
            EventExecutor executor, ByteBufAllocator alloc) {
        requireNonNull(executor, "executor");
        requireNonNull(alloc, "alloc");
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpRequestAggregator aggregator = new HttpRequestAggregator(this, future, alloc);
        completionFuture().handleAsync(aggregator, executor);
        subscribe(aggregator, executor, true);
        return future;
    }
}
