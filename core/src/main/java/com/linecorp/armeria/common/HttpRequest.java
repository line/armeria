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
import java.util.List;
import java.util.Locale;
import java.util.Locale.LanguageRange;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.FixedHttpRequest.EmptyFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.OneElementFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.RegularFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.TwoElementFixedHttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.common.DefaultHttpRequest;
import com.linecorp.armeria.internal.common.DefaultSplitHttpRequest;
import com.linecorp.armeria.internal.common.HttpMessageAggregator;
import com.linecorp.armeria.internal.common.stream.DecodedHttpStreamMessage;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * A streamed HTTP/2 {@link Request}.
 *
 * <p>Note: The initial {@link RequestHeaders} is not signaled to {@link Subscriber}s. It is readily available
 * via {@link #headers()}.
 */
public interface HttpRequest extends Request, HttpMessage {

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
    @FormatMethod
    static HttpRequest of(HttpMethod method, String path, MediaType mediaType,
                          @FormatString String format, Object... args) {
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
            content.close();

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
                         .contentLength(contentLength)
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
     *
     * <p>Note that the {@link HttpObject}s in the {@link Publisher} are not released when
     * {@link Subscription#cancel()} or {@link #abort()} is called. You should add a hook in order to
     * release the elements. See {@link PublisherBasedStreamMessage} for more information.
     */
    static HttpRequest of(RequestHeaders headers, Publisher<? extends HttpObject> publisher) {
        requireNonNull(headers, "headers");
        requireNonNull(publisher, "publisher");
        if (publisher instanceof HttpRequest) {
            return ((HttpRequest) publisher).withHeaders(headers);
        } else if (publisher instanceof StreamMessage) {
            //noinspection unchecked
            return new StreamMessageBasedHttpRequest(headers, (StreamMessage<? extends HttpObject>) publisher);
        } else {
            return new PublisherBasedHttpRequest(headers, publisher);
        }
    }

    /**
     * Returns a new {@link HttpRequestBuilder}.
     */
    static HttpRequestBuilder builder() {
        return new HttpRequestBuilder();
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
     * Returns a list of {@link LanguageRange}s that are specified in {@link HttpHeaderNames#ACCEPT_LANGUAGE}
     * in the order of client-side preferences. If the client does not send the header, this will contain only a
     * wild card {@link LanguageRange}.
     */
    @Nullable
    default List<LanguageRange> acceptLanguages() {
        return headers().acceptLanguages();
    }

    /**
     * Matches the {@link Locale}s supported by the server to
     * the {@link HttpHeaderNames#ACCEPT_LANGUAGE} and returning the best match
     * according to client preference. It does this via <s>Basic Filter</s>ing each
     * {@link LanguageRange} and picking the first match. This is the "classic"
     * algorithm described in
     * <a href="https://datatracker.ietf.org/doc/html/rfc2616#section-14.4">RFC2616 Accept-Language (obsoleted)</a>
     * and also referenced in
     * <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-5.3.5">RFC7231 Accept-Language</a>.
     * @param supportedLocales an {@link Iterable} of {@link Locale}s supported by the server.
     * @return The best matching {@link Locale} or {@code null} if no {@link Locale} matches.
     */
    @Nullable
    default Locale selectLocale(Iterable<Locale> supportedLocales) {
        return headers().selectLocale(supportedLocales);
    }

    /**
     * Matches the {@link Locale}s supported by the server to
     * the {@link HttpHeaderNames#ACCEPT_LANGUAGE} and returning the best match
     * according to client preference. It does this via <s>Basic Filter</s>ing each
     * {@link LanguageRange} and picking the first match. This is the "classic"
     * algorithm described in
     * <a href="https://datatracker.ietf.org/doc/html/rfc2616#section-14.4">RFC2616 Accept-Language (obsoleted)</a>
     * and also referenced in
     * <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-5.3.5">RFC7231 Accept-Language</a>.
     * @param supportedLocales {@link Locale}s supported by the server.
     * @return The best matching {@link Locale} or {@code null} if no {@link Locale} matches.
     */
    @Nullable
    default Locale selectLocale(Locale... supportedLocales) {
        return selectLocale(ImmutableList.copyOf(requireNonNull(supportedLocales, "supportedLocales")));
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
        return HttpMessageAggregator.aggregateRequest(this, executor, null);
    }

    /**
     * (Advanced users only) Aggregates this request. The returned {@link CompletableFuture} will be notified
     * when the content and the trailers of the request is received fully.
     * {@link AggregatedHttpRequest#content()} will return a pooled object, and the caller must ensure
     * to release it. If you don't know what this means, use {@link #aggregate()}.
     *
     * @see PooledObjects
     */
    @UnstableApi
    default CompletableFuture<AggregatedHttpRequest> aggregateWithPooledObjects(ByteBufAllocator alloc) {
        return aggregateWithPooledObjects(defaultSubscriberExecutor(), alloc);
    }

    /**
     * Aggregates this request. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the request is received fully. {@link AggregatedHttpRequest#content()} will
     * return a pooled object, and the caller must ensure to release it. If you don't know what this means,
     * use {@link #aggregate()}.
     */
    default CompletableFuture<AggregatedHttpRequest> aggregateWithPooledObjects(
            EventExecutor executor, ByteBufAllocator alloc) {
        requireNonNull(executor, "executor");
        requireNonNull(alloc, "alloc");
        return HttpMessageAggregator.aggregateRequest(this, executor, alloc);
    }

    @Override
    default HttpRequestDuplicator toDuplicator() {
        return toDuplicator(Flags.defaultMaxRequestLength());
    }

    @Override
    default HttpRequestDuplicator toDuplicator(EventExecutor executor) {
        return toDuplicator(executor, Flags.defaultMaxRequestLength());
    }

    @Override
    default HttpRequestDuplicator toDuplicator(long maxRequestLength) {
        return toDuplicator(defaultSubscriberExecutor(), maxRequestLength);
    }

    @Override
    default HttpRequestDuplicator toDuplicator(EventExecutor executor, long maxRequestLength) {
        requireNonNull(executor, "executor");
        return new DefaultHttpRequestDuplicator(this, executor, maxRequestLength);
    }

    /**
     * Returns a new {@link SplitHttpRequest} which splits a stream of {@link HttpObject}s into
     * {@link HttpData}s and an HTTP trailers.
     * {@link SplitHttpRequest#trailers()} might not complete until the entire request body is consumed
     * completely.
     */
    @CheckReturnValue
    default SplitHttpRequest split() {
        return split(defaultSubscriberExecutor());
    }

    /**
     * Returns a new {@link SplitHttpRequest} which splits a stream of {@link HttpObject}s into
     * {@link HttpData}s and an HTTP trailers.
     * {@link SplitHttpRequest#trailers()} might not complete until the entire request body is consumed
     * completely.
     */
    @CheckReturnValue
    default SplitHttpRequest split(EventExecutor executor) {
        return new DefaultSplitHttpRequest(this, requireNonNull(executor, "executor"));
    }

    @Override
    default <T> StreamMessage<T> decode(HttpDecoder<T> decoder, ByteBufAllocator alloc,
                                        Function<? super HttpData, ? extends ByteBuf> byteBufConverter) {
        return new DecodedHttpStreamMessage<>(this, decoder, alloc, byteBufConverter);
    }

    /**
     * Transforms the {@link RequestHeaders} of this {@link HttpRequest} by applying the specified
     * {@link Function}.
     *
     * <p>For example:<pre>{@code
     * HttpRequest request = HttpRequest.of(HttpMethod.GET, "/items");
     * HttpRequest transformed =
     *     request.mapHeaders(headers -> headers.toBuilder()
     *                                          .add("TraceId", "1")
     *                                          .build());
     * assert transformed.aggregate().join().headers().get("TraceId").equals("1");
     * }</pre>
     */
    default HttpRequest mapHeaders(Function<? super RequestHeaders, ? extends RequestHeaders> function) {
        requireNonNull(function, "function");
        final RequestHeaders transformed = function.apply(headers());
        requireNonNull(transformed, "function.apply() returned null");
        return withHeaders(transformed);
    }

    /**
     * Transforms the {@link HttpData}s emitted by this {@link HttpRequest} by applying the specified
     * {@link Function}.
     *
     * <p>For example:<pre>{@code
     * HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/items"),
     *                                      HttpData.ofUtf8("data1,data2"));
     * HttpRequest transformed = request.mapData(data -> {
     *     return HttpData.ofUtf8(data.toStringUtf8().replaceAll(",", "\n"));
     * });
     * assert transformed.aggregate().join().contentUtf8().equals("data1\ndata2");
     * }</pre>
     */
    @Override
    default HttpRequest mapData(Function<? super HttpData, ? extends HttpData> function) {
        requireNonNull(function, "function");
        final StreamMessage<HttpObject> stream =
                map(obj -> obj instanceof HttpData ? function.apply((HttpData) obj) : obj);
        return of(headers(), stream);
    }

    /**
     * Transforms the {@linkplain HttpHeaders trailers} emitted by this {@link HttpRequest} by applying the
     * specified {@link Function}.
     *
     * <p>For example:<pre>{@code
     * HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/items"),
     *                                      HttpData.ofUtf8("..."));
     * HttpRequest transformed = request.mapTrailers(trailers -> {
     *     return trailers.withMutations(builder -> builder.set("trailer1", "foo"));
     * });
     * assert transformed.aggregate().join().trailers().get("trailer1").equals("foo");
     * }</pre>
     */
    @Override
    default HttpRequest mapTrailers(Function<? super HttpHeaders, ? extends HttpHeaders> function) {
        requireNonNull(function, "function");

        final StreamMessage<HttpObject> stream = map(obj -> {
            if (obj instanceof HttpHeaders) {
                return function.apply((HttpHeaders) obj);
            }
            return obj;
        });
        return of(headers(), stream);
    }

    /**
     * Transforms an error emitted by this {@link HttpRequest} by applying the specified {@link Function}.
     *
     * <p>For example:<pre>{@code
     * HttpRequest request = HttpRequest.ofFailure(new IllegalStateException("Something went wrong."));
     * HttpRequest transformed = request.mapError(cause -> {
     *     if (cause instanceof IllegalStateException) {
     *         return new MyDomainException(cause);
     *     } else {
     *         return cause;
     *     }
     * });
     * }</pre>
     */
    @Override
    default HttpRequest mapError(Function<? super Throwable, ? extends Throwable> function) {
        requireNonNull(function, "function");
        return of(headers(), HttpMessage.super.mapError(function));
    }

    /**
     * Applies the specified {@link Consumer} to the {@link HttpData}s
     * emitted by this {@link HttpRequest}.
     *
     * <p>For example:<pre>{@code
     * HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/items"),
     *                                      HttpData.ofUtf8("data1,data2"));
     * HttpRequest peeked = request.peekData(data -> {
     *     assert data.toStringUtf8().equals("data1,data2");
     * });
     * }</pre>
     */
    @Override
    @UnstableApi
    default HttpRequest peekData(Consumer<? super HttpData> action) {
        requireNonNull(action, "action");
        final StreamMessage<HttpObject> stream = peek(action, HttpData.class);
        return of(headers(), stream);
    }

    /**
     * Applies the specified {@link Consumer} to the {@linkplain HttpHeaders trailers}
     * emitted by this {@link HttpRequest}.
     *
     * <p>For example:<pre>{@code
     * HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/items"),
     *                                      HttpData.ofUtf8("..."),
     *                                      HttpHeaders.of("trailer", "foo"));
     * HttpRequest peeked = request.peekTrailers(trailers -> {
     *     assert trailers.get("trailer").equals("foo");
     * });
     * }</pre>
     */
    @Override
    @UnstableApi
    default HttpRequest peekTrailers(Consumer<? super HttpHeaders> action) {
        requireNonNull(action, "action");
        final StreamMessage<HttpObject> stream = peek(action, HttpHeaders.class);
        return of(headers(), stream);
    }

    /**
     * Applies the specified {@link Consumer} to an error emitted by this {@link HttpRequest}.
     *
     * <p>For example:<pre>{@code
     * HttpRequest request = HttpRequest.ofFailure(new IllegalStateException("Something went wrong."));
     * HttpRequest peeked = request.peekError(cause -> {
     *     assert cause instanceof IllegalStateException;
     * });
     * }</pre>
     */
    @Override
    @UnstableApi
    default HttpRequest peekError(Consumer<? super Throwable> action) {
        requireNonNull(action, "action");
        return of(headers(), HttpMessage.super.peekError(action));
    }
}
