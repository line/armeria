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

import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.internal.common.DefaultHttpRequest;
import com.linecorp.armeria.internal.common.HttpRequestAggregator;

import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;

/**
 * A streamed HTTP/2 {@link Request}.
 *
 * <p>Note: The initial {@link RequestHeaders} is not signaled to {@link Subscriber}s. It is readily available
 * via {@link #headers()}.
 */
public interface HttpRequest extends Request, StreamMessage<HttpObject> {

    // Note: Ensure we provide the same set of `of()` methods with the `of()` methods of
    //       AggregatedHttpRequest for consistency.

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
        requireNonNull(content, "content");
        requireNonNull(mediaType, "mediaType");
        return of(method, path, mediaType,
                  HttpData.of(mediaType.charset(StandardCharsets.UTF_8), content));
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
                  HttpData.of(mediaType.charset(StandardCharsets.UTF_8), content));
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
                  HttpData.of(mediaType.charset(StandardCharsets.UTF_8), format, args));
    }

    /**
     * Creates a new HTTP request and closes the stream. The {@code content} will be wrapped using
     * {@link HttpData#wrap(byte[])}, so any changes made to {@code content} will be reflected in the request.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     */
    static HttpRequest of(HttpMethod method, String path, MediaType mediaType, byte[] content) {
        requireNonNull(content, "content");
        return of(method, path, mediaType, HttpData.wrap(content));
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
     * @param trailers the HTTP trailers
     */
    static HttpRequest of(HttpMethod method, String path, MediaType mediaType, HttpData content,
                          HttpHeaders trailers) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        return of(RequestHeaders.builder(method, path)
                                .contentType(mediaType)
                                .build(),
                  content, trailers);
    }

    /**
     * Creates a new {@link HttpRequest} with empty content and closes the stream.
     */
    static HttpRequest of(RequestHeaders headers) {
        return of(headers, HttpData.empty());
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
    static HttpRequest of(RequestHeaders headers, HttpData content, HttpHeaders trailers) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        requireNonNull(trailers, "trailers");

        final int contentLength = content.length();
        if (contentLength == 0) {
            ReferenceCountUtil.release(content);

            headers = headers.toBuilder()
                             .removeAndThen(CONTENT_LENGTH)
                             .build();

            if (!trailers.isEmpty()) {
                return new OneElementFixedHttpRequest(headers, trailers);
            } else {
                return new EmptyFixedHttpRequest(headers);
            }
        }

        // `content` is not empty.
        headers = headers.toBuilder()
                         .setInt(CONTENT_LENGTH, contentLength)
                         .build();

        if (trailers.isEmpty()) {
            return new OneElementFixedHttpRequest(headers, content);
        } else {
            return new TwoElementFixedHttpRequest(headers, content, trailers);
        }
    }

    /**
     * Creates a new {@link HttpRequest} that publishes the given {@link HttpObject}s and closes the stream.
     */
    static HttpRequest of(RequestHeaders headers, HttpData... contents) {
        requireNonNull(headers, "headers");
        requireNonNull(contents, "contents");
        switch (contents.length) {
            case 0:
                return new EmptyFixedHttpRequest(headers);
            case 1:
                return new OneElementFixedHttpRequest(headers, contents[0]);
            case 2:
                return new TwoElementFixedHttpRequest(headers, contents[0], contents[1]);
            default:
                return new RegularFixedHttpRequest(headers, contents);
        }
    }

    /**
     * Creates a new instance from an existing {@link RequestHeaders} and {@link Publisher}.
     */
    static HttpRequest of(RequestHeaders headers, Publisher<? extends HttpObject> publisher) {
        requireNonNull(headers, "headers");
        requireNonNull(publisher, "publisher");
        if (publisher instanceof HttpRequest) {
            return ((HttpRequest) publisher).withHeaders(headers);
        } else {
            return new PublisherBasedHttpRequest(headers, publisher);
        }
    }

    /**
     * Returns the initial HTTP/2 headers of this request.
     */
    RequestHeaders headers();

    /**
     * Returns the URI of this request. This method is a shortcut for {@code headers().uri()}.
     */
    default URI uri() {
        return headers().uri();
    }

    /**
     * Returns the scheme of this request. This method is a shortcut for {@code headers().scheme()}.
     */
    @Nullable
    default String scheme() {
        return headers().scheme();
    }

    /**
     * Returns the method of this request. This method is a shortcut for {@code headers().method()}.
     */
    default HttpMethod method() {
        return headers().method();
    }

    /**
     * Returns the path of this request. This method is a shortcut for {@code headers().path()}.
     */
    default String path() {
        return headers().path();
    }

    /**
     * Returns the authority of this request. This method is a shortcut for {@code headers().authority()}.
     */
    @Nullable
    default String authority() {
        return headers().authority();
    }

    /**
     * Returns the value of the {@code 'content-type'} header.
     * @return the valid header value if present, or {@code null} otherwise.
     */
    @Nullable
    default MediaType contentType() {
        return headers().contentType();
    }

    /**
     * Returns a new {@link HttpRequest} derived from this {@link HttpRequest} by replacing its
     * {@link RequestHeaders} with the specified {@code newHeaders}. Note that the content stream and trailers
     * of this {@link HttpRequest} is not duplicated, which means you can subscribe to only one of the two
     * {@link HttpRequest}s.
     *
     * <p>If you are using this method for intercepting an {@link HttpRequest} in a decorator, make sure to
     * update {@link RequestContext#request()} with {@link RequestContext#updateRequest(HttpRequest)}, e.g.
     * <pre>{@code
     * > public class MyService extends SimpleDecoratingHttpService {
     * >     @Override
     * >     public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
     * >         // Create a new request with an additional header.
     * >         final HttpRequest newReq =
     * >                 req.withHeaders(req.headers().toBuilder()
     * >                                    .set("x-custom-header", "value")
     * >                                    .build());
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
    default HttpRequest withHeaders(RequestHeaders newHeaders) {
        requireNonNull(newHeaders, "newHeaders");
        if (headers() == newHeaders) {
            // Just check the reference only to avoid heavy comparison.
            return this;
        }

        return new HeaderOverridingHttpRequest(this, newHeaders);
    }

    /**
     * Returns a new {@link HttpRequest} derived from this {@link HttpRequest} by replacing its
     * {@link RequestHeaders} with what's built from the specified {@code newHeadersBuilder}.
     * Note that the content stream and trailers of this {@link HttpRequest} is not duplicated,
     * which means you can subscribe to only one of the two {@link HttpRequest}s.
     *
     * <p>If you are using this method for intercepting an {@link HttpRequest} in a decorator, make sure to
     * update {@link RequestContext#request()} with {@link RequestContext#updateRequest(HttpRequest)}, e.g.
     * <pre>{@code
     * > public class MyService extends SimpleDecoratingHttpService {
     * >     @Override
     * >     public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
     * >         // Create a new request with an additional header.
     * >         final HttpRequest newReq =
     * >                 req.withHeaders(req.headers().toBuilder()
     * >                                    .set("x-custom-header", "value"));
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
    default HttpRequest withHeaders(RequestHeadersBuilder newHeadersBuilder) {
        requireNonNull(newHeadersBuilder, "newHeadersBuilder");
        return withHeaders(newHeadersBuilder.build());
    }

    /**
     * Aggregates this request. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the request is received fully.
     */
    default CompletableFuture<AggregatedHttpRequest> aggregate() {
        return aggregate(defaultSubscriberExecutor());
    }

    /**
     * Aggregates this request. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the request is received fully.
     */
    default CompletableFuture<AggregatedHttpRequest> aggregate(EventExecutor executor) {
        requireNonNull(executor, "executor");
        final CompletableFuture<AggregatedHttpRequest> future = new EventLoopCheckingFuture<>();
        final HttpRequestAggregator aggregator = new HttpRequestAggregator(this, future, null);
        subscribe(aggregator, executor);
        return future;
    }

    /**
     * Returns a new {@link HttpRequestDuplicator} that duplicates this {@link HttpRequest} into one or
     * more {@link HttpRequest}s, which publish the same elements.
     * Note that you cannot subscribe to this {@link HttpRequest} anymore after you call this method.
     * To subscribe, call {@link HttpRequestDuplicator#duplicate()} from the returned
     * {@link HttpRequestDuplicator}.
     */
    @Override
    default HttpRequestDuplicator toDuplicator() {
        return toDuplicator(Flags.defaultMaxRequestLength());
    }

    /**
     * Returns a new {@link HttpRequestDuplicator} that duplicates this {@link HttpRequest} into one or
     * more {@link HttpRequest}s, which publish the same elements.
     * Note that you cannot subscribe to this {@link HttpRequest} anymore after you call this method.
     * To subscribe, call {@link HttpRequestDuplicator#duplicate()} from the returned
     * {@link HttpRequestDuplicator}.
     *
     * @param executor the executor to duplicate
     */
    @Override
    default HttpRequestDuplicator toDuplicator(EventExecutor executor) {
        return toDuplicator(executor, Flags.defaultMaxRequestLength());
    }

    /**
     * Returns a new {@link HttpRequestDuplicator} that duplicates this {@link HttpRequest} into one or
     * more {@link HttpRequest}s, which publish the same elements.
     * Note that you cannot subscribe to this {@link HttpRequest} anymore after you call this method.
     * To subscribe, call {@link HttpRequestDuplicator#duplicate()} from the returned
     * {@link HttpRequestDuplicator}.
     *
     * @param maxRequestLength the maximum request length that the duplicator can hold in its buffer.
     *                         {@link ContentTooLargeException} is raised if the length of the buffered
     *                         {@link HttpData} is greater than this value.
     */
    default HttpRequestDuplicator toDuplicator(long maxRequestLength) {
        return toDuplicator(defaultSubscriberExecutor(), maxRequestLength);
    }

    /**
     * Returns a new {@link HttpRequestDuplicator} that duplicates this {@link HttpRequest} into one or
     * more {@link HttpRequest}s, which publish the same elements.
     * Note that you cannot subscribe to this {@link HttpRequest} anymore after you call this method.
     * To subscribe, call {@link HttpRequestDuplicator#duplicate()} from the returned
     * {@link HttpRequestDuplicator}.
     *
     * @param executor the executor to duplicate
     * @param maxRequestLength the maximum request length that the duplicator can hold in its buffer.
     *                         {@link ContentTooLargeException} is raised if the length of the buffered
     *                         {@link HttpData} is greater than this value.
     */
    default HttpRequestDuplicator toDuplicator(EventExecutor executor, long maxRequestLength) {
        requireNonNull(executor, "executor");
        return new DefaultHttpRequestDuplicator(this, executor, maxRequestLength);
    }
}
