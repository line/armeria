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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.setOrRemoveContentLength;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Formatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.FixedHttpResponse.OneElementFixedHttpResponse;
import com.linecorp.armeria.common.FixedHttpResponse.RegularFixedHttpResponse;
import com.linecorp.armeria.common.FixedHttpResponse.ThreeElementFixedHttpResponse;
import com.linecorp.armeria.common.FixedHttpResponse.TwoElementFixedHttpResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.common.AbortedHttpResponse;
import com.linecorp.armeria.internal.common.DefaultHttpResponse;
import com.linecorp.armeria.internal.common.DefaultSplitHttpResponse;
import com.linecorp.armeria.internal.common.HttpMessageAggregator;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.internal.common.stream.DecodedHttpStreamMessage;
import com.linecorp.armeria.internal.common.stream.RecoverableStreamMessage;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

/**
 * A streamed HTTP/2 {@link Response}.
 */
public interface HttpResponse extends Response, HttpMessage {

    // Note: Ensure we provide the same set of `of()` methods with the `of()` methods of
    //       AggregatedHttpResponse for consistency.

    /**
     * Creates a new HTTP response that can stream an arbitrary number of {@link HttpObject} to the client.
     * The first object written must be of type {@link ResponseHeaders}.
     */
    static HttpResponseWriter streaming() {
        return new DefaultHttpResponse();
    }

    /**
     * Creates a new HTTP response that delegates to the {@link HttpResponse} produced by the specified
     * {@link CompletionStage}. If the specified {@link CompletionStage} fails, the returned response will be
     * closed with the same cause as well.
     *
     * @param stage the {@link CompletionStage} which will produce the actual {@link HttpResponse}
     */
    static HttpResponse from(CompletionStage<? extends HttpResponse> stage) {
        requireNonNull(stage, "stage");
        final DeferredHttpResponse res = new DeferredHttpResponse();
        res.delegateWhenComplete(stage);
        return res;
    }

    /**
     * Creates a new HTTP response that delegates to the {@link HttpResponse} produced by the specified
     * {@link CompletionStage}. If the specified {@link CompletionStage} fails, the returned response will be
     * closed with the same cause as well.
     *
     * @param stage the {@link CompletionStage} which will produce the actual {@link HttpResponse}
     * @param subscriberExecutor the {@link EventExecutor} which will be used when a user subscribes
     *                           the returned {@link HttpResponse} using {@link #subscribe(Subscriber)}
     *                           or {@link #subscribe(Subscriber, SubscriptionOption...)}.
     */
    static HttpResponse from(CompletionStage<? extends HttpResponse> stage,
                             EventExecutor subscriberExecutor) {
        requireNonNull(stage, "stage");
        requireNonNull(subscriberExecutor, "subscriberExecutor");
        final DeferredHttpResponse res = new DeferredHttpResponse(subscriberExecutor);
        res.delegateWhenComplete(stage);
        return res;
    }

    /**
     * Creates a new HTTP response that delegates to the {@link HttpResponse} provided by the {@link Supplier}.
     *
     * @param responseSupplier the {@link Supplier} invokes returning the provided {@link HttpResponse}
     * @param executor the {@link Executor} that executes the {@link Supplier}.
     */
    static HttpResponse from(Supplier<? extends HttpResponse> responseSupplier, Executor executor) {
        requireNonNull(responseSupplier, "responseSupplier");
        requireNonNull(executor, "executor");
        final DeferredHttpResponse res = new DeferredHttpResponse();
        executor.execute(() -> {
            try {
                res.delegate(responseSupplier.get());
            } catch (Throwable ex) {
                res.abort(ex);
            }
        });
        return res;
    }

    /**
     * Creates a new HTTP response that delegates to the provided {@link AggregatedHttpResponse}, beginning
     * publishing after {@code delay} has passed from a random {@link ScheduledExecutorService}.
     */
    static HttpResponse delayed(AggregatedHttpResponse response, Duration delay) {
        requireNonNull(response, "response");
        requireNonNull(delay, "delay");
        return delayed(response.toHttpResponse(), delay);
    }

    /**
     * Creates a new HTTP response that delegates to the provided {@link AggregatedHttpResponse}, beginning
     * publishing after {@code delay} has passed from the provided {@link ScheduledExecutorService}.
     */
    static HttpResponse delayed(AggregatedHttpResponse response, Duration delay,
                                ScheduledExecutorService executor) {
        requireNonNull(response, "response");
        requireNonNull(delay, "delay");
        requireNonNull(executor, "executor");
        return delayed(response.toHttpResponse(), delay, executor);
    }

    /**
     * Creates a new HTTP response that delegates to the provided {@link HttpResponse}, beginning publishing
     * after {@code delay} has passed from a random {@link ScheduledExecutorService}.
     */
    static HttpResponse delayed(HttpResponse response, Duration delay) {
        requireNonNull(response, "response");
        requireNonNull(delay, "delay");
        return delayed(() -> response, delay);
    }

    /**
     * Creates a new HTTP response that delegates to the provided {@link HttpResponse}, beginning publishing
     * after {@code delay} has passed from the provided {@link ScheduledExecutorService}.
     */
    static HttpResponse delayed(HttpResponse response, Duration delay, ScheduledExecutorService executor) {
        requireNonNull(response, "response");
        requireNonNull(delay, "delay");
        requireNonNull(executor, "executor");
        return delayed(() -> response, delay, executor);
    }

    /**
     * Invokes the specified {@link Supplier} and creates a new HTTP response that
     * delegates to the provided {@link HttpResponse} by {@link Supplier}.
     *
     * <p>The {@link Supplier} is invoked from the current thread-local {@link RequestContext}'s event loop.
     * If there's no thread local {@link RequestContext} is set, one of the threads
     * from {@code CommonPools.workerGroup().next()} will be used.
     */
    static HttpResponse delayed(Supplier<? extends HttpResponse> responseSupplier, Duration delay) {
        requireNonNull(responseSupplier, "responseSupplier");
        requireNonNull(delay, "delay");
        return delayed(responseSupplier, delay,
                       RequestContext.mapCurrent(RequestContext::eventLoop,
                                                 CommonPools.workerGroup()::next));
    }

    /**
     * Invokes the specified {@link Supplier} and creates a new HTTP response that
     * delegates to the provided {@link HttpResponse} {@link Supplier},
     * beginning publishing after {@code delay} has passed from the provided {@link ScheduledExecutorService}.
     */
    static HttpResponse delayed(Supplier<? extends HttpResponse> responseSupplier,
                                Duration delay,
                                ScheduledExecutorService executor) {
        requireNonNull(responseSupplier, "responseSupplier");
        requireNonNull(delay, "delay");
        requireNonNull(executor, "executor");
        final DeferredHttpResponse res = new DeferredHttpResponse();
        executor.schedule(() -> {
            try {
                res.delegate(responseSupplier.get());
            } catch (Throwable ex) {
                res.abort(ex);
            }
        }, delay.toNanos(), TimeUnit.NANOSECONDS);
        return res;
    }

    /**
     * Creates a new HTTP response of the specified {@code statusCode}.
     *
     * @throws IllegalArgumentException if the specified {@code statusCode} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static HttpResponse of(int statusCode) {
        return of(HttpStatus.valueOf(statusCode));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     *
     * @throws IllegalArgumentException if the specified {@link HttpStatus} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static HttpResponse of(HttpStatus status) {
        requireNonNull(status, "status");
        checkArgument(!status.isInformational(), "status: %s (expected: a non-1xx status)", status);

        if (status.isContentAlwaysEmpty()) {
            return new OneElementFixedHttpResponse(ResponseHeaders.of(status));
        } else {
            return of(status, MediaType.PLAIN_TEXT_UTF_8, status.toHttpData());
        }
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     *
     * @throws IllegalArgumentException if the specified {@link HttpStatus} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, CharSequence content) {
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(status, mediaType,
                  HttpData.of(mediaType.charset(StandardCharsets.UTF_8), content));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     *
     * @throws IllegalArgumentException if the specified {@link HttpStatus} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, String content) {
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(status, mediaType,
                  HttpData.of(mediaType.charset(StandardCharsets.UTF_8), content));
    }

    /**
     * Creates a new HTTP response of OK status with the content as UTF_8.
     *
     * @param content the content of the response
     */
    static HttpResponse of(String content) {
        return of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, content);
    }

    /**
     * Creates a new HTTP response of OK status with the content as UTF_8.
     * The content of the response is formatted by {@link String#format(Locale, String, Object...)} with
     * {@linkplain Locale#ENGLISH English locale}.
     *
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     */
    @FormatMethod
    static HttpResponse of(@FormatString String format, Object... args) {
        return of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, format, args);
    }

    /**
     * Creates a new HTTP response of OK status with the content.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static HttpResponse of(MediaType mediaType, String content) {
        return of(HttpStatus.OK, mediaType, content);
    }

    /**
     * Creates a new HTTP response of OK status with the content.
     * The content of the response is formatted by {@link String#format(Locale, String, Object...)} with
     * {@linkplain Locale#ENGLISH English locale}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     */
    @FormatMethod
    static HttpResponse of(MediaType mediaType, @FormatString String format, Object... args) {
        return of(HttpStatus.OK, mediaType, format, args);
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     * The content of the response is formatted by {@link String#format(Locale, String, Object...)} with
     * {@linkplain Locale#ENGLISH English locale}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     *
     * @throws IllegalArgumentException if the specified {@link HttpStatus} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    @FormatMethod
    static HttpResponse of(HttpStatus status, MediaType mediaType,
                           @FormatString String format, Object... args) {
        requireNonNull(mediaType, "mediaType");
        return of(status, mediaType, HttpData.of(mediaType.charset(StandardCharsets.UTF_8), format, args));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}. The {@code content} will be wrapped
     * using {@link HttpData#wrap(byte[])}, so any changes made to {@code content} will be reflected in the
     * response.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     *
     * @throws IllegalArgumentException if the specified {@link HttpStatus} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, byte[] content) {
        requireNonNull(content, "content");
        return of(status, mediaType, HttpData.wrap(content));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     *
     * @throws IllegalArgumentException if the specified {@link HttpStatus} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, HttpData content) {
        return of(status, mediaType, content, HttpHeaders.of());
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     * @param trailers the HTTP trailers
     *
     * @throws IllegalArgumentException if the specified {@link HttpStatus} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, HttpData content,
                           HttpHeaders trailers) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");

        final ResponseHeaders headers = ResponseHeaders.builder(status)
                                                       .contentType(mediaType)
                                                       .build();
        return of(headers, content, trailers);
    }

    /**
     * Creates a new HTTP response of the specified headers.
     *
     * @throws IllegalArgumentException if the status of the specified {@link ResponseHeaders} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static HttpResponse of(ResponseHeaders headers) {
        return of(headers, HttpData.empty());
    }

    /**
     * Creates a new HTTP response of the specified headers and content.
     *
     * @throws IllegalArgumentException if the status of the specified {@link ResponseHeaders} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static HttpResponse of(ResponseHeaders headers, HttpData content) {
        return of(headers, content, HttpHeaders.of());
    }

    /**
     * Creates a new HTTP response of the specified objects.
     *
     * @throws IllegalArgumentException if the status of the specified {@link ResponseHeaders} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static HttpResponse of(ResponseHeaders headers, HttpData content, HttpHeaders trailers) {
        requireNonNull(headers, "headers");
        final HttpStatus status = headers.status();
        checkArgument(!status.isInformational(), "status: %s (expected: a non-1xx status)", status);

        requireNonNull(content, "content");
        requireNonNull(trailers, "trailers");

        final ResponseHeaders newHeaders = setOrRemoveContentLength(headers, content, trailers);
        final boolean contentIsEmpty = content.isEmpty();
        if (contentIsEmpty) {
            content.close();
            if (trailers.isEmpty()) {
                return new OneElementFixedHttpResponse(newHeaders);
            } else {
                return new TwoElementFixedHttpResponse(newHeaders, trailers);
            }
        }

        // `content` is not empty from now on.

        if (trailers.isEmpty()) {
            return new TwoElementFixedHttpResponse(newHeaders, content);
        } else {
            return new ThreeElementFixedHttpResponse(newHeaders, content, trailers);
        }
    }

    /**
     * Creates a new HTTP response of the specified objects.
     */
    static HttpResponse of(HttpObject... objs) {
        return new RegularFixedHttpResponse(objs);
    }

    /**
     * Creates a new HTTP response whose stream is produced from an existing {@link Publisher}.
     *
     * <p>Note that the {@link HttpObject}s in the {@link Publisher} are not released when
     * {@link Subscription#cancel()} or {@link #abort()} is called. You should add a hook in order to
     * release the elements. See {@link PublisherBasedStreamMessage} for more information.
     */
    static HttpResponse of(Publisher<? extends HttpObject> publisher) {
        requireNonNull(publisher, "publisher");
        if (publisher instanceof HttpResponse) {
            return (HttpResponse) publisher;
        } else if (publisher instanceof StreamMessage) {
            //noinspection unchecked
            return new StreamMessageBasedHttpResponse((StreamMessage<? extends HttpObject>) publisher);
        } else {
            return new PublisherBasedHttpResponse(publisher);
        }
    }

    /**
     * Creates a new HTTP response with the specified headers whose stream is produced from an existing
     * {@link Publisher}.
     *
     * <p>Note that the {@link HttpObject}s in the {@link Publisher} are not released when
     * {@link Subscription#cancel()} or {@link #abort()} is called. You should add a hook in order to
     * release the elements. See {@link PublisherBasedStreamMessage} for more information.
     */
    static HttpResponse of(ResponseHeaders headers, Publisher<? extends HttpObject> publisher) {
        requireNonNull(headers, "headers");
        requireNonNull(publisher, "publisher");
        return PublisherBasedHttpResponse.from(headers, publisher);
    }

    /**
     * Creates a new HTTP response with the specified {@code content} that is converted into JSON using the
     * default {@link ObjectMapper}.
     *
     * @throws IllegalArgumentException if failed to encode the {@code content} into JSON.
     * @see JacksonObjectMapperProvider
     */
    static HttpResponse ofJson(Object content) {
        return ofJson(HttpStatus.OK, content);
    }

    /**
     * Creates a new HTTP response with the specified {@link HttpStatus} and {@code content} that is
     * converted into JSON using the default {@link ObjectMapper}.
     *
     * @throws IllegalArgumentException if failed to encode the {@code content} into JSON.
     * @see JacksonObjectMapperProvider
     */
    static HttpResponse ofJson(HttpStatus status, Object content) {
        requireNonNull(status, "status");
        final ResponseHeaders headers = ResponseHeaders.builder(status)
                                                       .contentType(MediaType.JSON)
                                                       .build();
        return ofJson(headers, content);
    }

    /**
     * Creates a new HTTP response with the specified {@link MediaType} and {@code content} that is
     * converted into JSON using the default {@link ObjectMapper}.
     *
     * @throws IllegalArgumentException if the specified {@link MediaType} is not a JSON compatible type; or
     *                                  if failed to encode the {@code content} into JSON.
     * @see JacksonObjectMapperProvider
     */
    static HttpResponse ofJson(MediaType contentType, Object content) {
        return ofJson(HttpStatus.OK, contentType, content);
    }

    /**
     * Creates a new HTTP response with the specified {@link HttpStatus}, {@link MediaType} and
     * {@code content} that is converted into JSON using the default {@link ObjectMapper}.
     *
     * @throws IllegalArgumentException if the specified {@link MediaType} is not a JSON compatible type; or
     *                                  if failed to encode the {@code content} into JSON.
     * @see JacksonObjectMapperProvider
     */
    static HttpResponse ofJson(HttpStatus status, MediaType contentType, Object content) {
        requireNonNull(status, "status");
        requireNonNull(contentType, "contentType");
        checkArgument(contentType.isJson(),
                      "contentType: %s (expected: the subtype is 'json' or ends with '+json'.", contentType);
        final ResponseHeaders headers = ResponseHeaders.builder(status)
                                                       .contentType(contentType)
                                                       .build();
        return ofJson(headers, content);
    }

    /**
     * Creates a new HTTP response with the specified {@link ResponseHeaders} and {@code content} that is
     * converted into JSON using the default {@link ObjectMapper}.
     *
     * @throws IllegalArgumentException if failed to encode the {@code content} into JSON.
     * @see JacksonObjectMapperProvider
     */
    static HttpResponse ofJson(ResponseHeaders headers, Object content) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");

        final HttpData httpData;
        try {
            httpData = HttpData.wrap(JacksonUtil.writeValueAsBytes(content));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.toString(), e);
        }

        final MediaType contentType = headers.contentType();
        if (contentType != null && contentType.isJson()) {
            return of(headers, httpData);
        } else {
            final ResponseHeaders newHeaders = headers.toBuilder()
                                                      .contentType(MediaType.JSON)
                                                      .build();
            return of(newHeaders, httpData);
        }
    }

    /**
     * Creates a new HTTP response of the redirect to specific location.
     */
    static HttpResponse ofRedirect(HttpStatus redirectStatus, String location) {
        requireNonNull(redirectStatus, "redirectStatus");
        requireNonNull(location, "location");
        if (redirectStatus.compareTo(HttpStatus.MULTIPLE_CHOICES) < 0 ||
            redirectStatus.compareTo(HttpStatus.TEMPORARY_REDIRECT) > 0) {
            throw new IllegalArgumentException("redirectStatus: " + redirectStatus + " (expected: 300 .. 307)");
        }

        return of(ResponseHeaders.of(redirectStatus, HttpHeaderNames.LOCATION, location));
    }

    /**
     * Creates a new HTTP response of the redirect to specific location using string format.
     */
    static HttpResponse ofRedirect(HttpStatus redirectStatus, String format, Object... args) {
        requireNonNull(format, "format");
        requireNonNull(args, "args");

        return ofRedirect(redirectStatus, String.format(format, args));
    }

    /**
     * Creates a new HTTP response of the temporary redirect to specific location.
     */
    static HttpResponse ofRedirect(String location) {
        return ofRedirect(HttpStatus.TEMPORARY_REDIRECT, location);
    }

    /**
     * Creates a new HTTP response of the temporary redirect to specific location using string format.
     */
    static HttpResponse ofRedirect(String format, Object... args) {
        requireNonNull(format, "format");
        requireNonNull(args, "args");

        return ofRedirect(HttpStatus.TEMPORARY_REDIRECT, String.format(format, args));
    }

    /**
     * Creates a new failed HTTP response.
     */
    static HttpResponse ofFailure(Throwable cause) {
        return new AbortedHttpResponse(cause);
    }

    @Override
    CompletableFuture<Void> whenComplete();

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the response are received fully.
     */
    default CompletableFuture<AggregatedHttpResponse> aggregate() {
        return aggregate(defaultSubscriberExecutor());
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the response are received fully.
     */
    default CompletableFuture<AggregatedHttpResponse> aggregate(EventExecutor executor) {
        return HttpMessageAggregator.aggregateResponse(this, executor, null);
    }

    /**
     * (Advanced users only) Aggregates this response. The returned {@link CompletableFuture} will be notified
     * when the content and the trailers of the response are received fully.
     * {@link AggregatedHttpResponse#content()} will return a pooled object, and the caller must ensure
     * to release it. If you don't know what this means, use {@link #aggregate()}.
     *
     * @see PooledObjects
     */
    @UnstableApi
    default CompletableFuture<AggregatedHttpResponse> aggregateWithPooledObjects(ByteBufAllocator alloc) {
        return aggregateWithPooledObjects(defaultSubscriberExecutor(), alloc);
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the request is received fully. {@link AggregatedHttpResponse#content()} will
     * return a pooled object, and the caller must ensure to release it. If you don't know what this means,
     * use {@link #aggregate()}.
     */
    default CompletableFuture<AggregatedHttpResponse> aggregateWithPooledObjects(
            EventExecutor executor, ByteBufAllocator alloc) {
        requireNonNull(executor, "executor");
        requireNonNull(alloc, "alloc");
        return HttpMessageAggregator.aggregateResponse(this, executor, alloc);
    }

    @Override
    default HttpResponseDuplicator toDuplicator() {
        return toDuplicator(Flags.defaultMaxResponseLength());
    }

    @Override
    default HttpResponseDuplicator toDuplicator(EventExecutor executor) {
        return toDuplicator(executor, Flags.defaultMaxResponseLength());
    }

    @Override
    default HttpResponseDuplicator toDuplicator(long maxResponseLength) {
        return toDuplicator(defaultSubscriberExecutor(), maxResponseLength);
    }

    @Override
    default HttpResponseDuplicator toDuplicator(EventExecutor executor, long maxResponseLength) {
        requireNonNull(executor, "executor");
        return new DefaultHttpResponseDuplicator(this, executor, maxResponseLength);
    }

    /**
     * Returns a new {@link SplitHttpResponse} which splits a stream of {@link HttpObject}s into
     * {@link HttpHeaders} and {@link HttpData}.
     * {@link SplitHttpResponse#headers()} will be
     * completed before publishing the first {@link HttpData}.
     * {@link SplitHttpResponse#trailers()} might not complete until the entire response body is consumed
     * completely.
     */
    @CheckReturnValue
    default SplitHttpResponse split() {
        return split(defaultSubscriberExecutor());
    }

    /**
     * Returns a new {@link SplitHttpResponse} which splits a stream of {@link HttpObject}s into
     * {@link HttpHeaders} and {@link HttpData}.
     * {@link SplitHttpResponse#headers()} will be completed before publishing the first {@link HttpData}.
     * {@link SplitHttpResponse#trailers()} might not complete until the entire response body is consumed
     * completely.
     */
    @CheckReturnValue
    default SplitHttpResponse split(EventExecutor executor) {
        return new DefaultSplitHttpResponse(this, executor);
    }

    @Override
    default <T> StreamMessage<T> decode(HttpDecoder<T> decoder, ByteBufAllocator alloc,
                                        Function<? super HttpData, ? extends ByteBuf> byteBufConverter) {
        return new DecodedHttpStreamMessage<>(this, decoder, alloc, byteBufConverter);
    }

    /**
     * Transforms the
     * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status#Information_responses">informational headers</a>
     * emitted by {@link HttpResponse} by applying the specified {@link Function}.
     */
    default HttpResponse mapInformational(
            Function<? super ResponseHeaders, ? extends ResponseHeaders> function) {
        requireNonNull(function, "function");
        final StreamMessage<HttpObject> stream = map(obj -> {
            if (obj instanceof ResponseHeaders) {
                final ResponseHeaders headers = (ResponseHeaders) obj;
                if (headers.status().isInformational()) {
                    return function.apply(headers);
                }
            }
            return obj;
        });
        return of(stream);
    }

    /**
     * Transforms the non-informational {@link ResponseHeaders} emitted by {@link HttpResponse} by applying
     * the specified {@link Function}.
     *
     * <p>For example:<pre>{@code
     * HttpResponse response = HttpResponse.of("OK");
     * HttpResponse transformed = response.mapHeaders(headers -> {
     *     return headers.withMutations(builder -> {
     *         builder.set(HttpHeaderNames.USER_AGENT, "my-server");
     *     });
     * });
     * assert transformed.aggregate().join().headers()
     *                   .get(HttpHeaderNames.USER_AGENT).equals("my-server");
     * }</pre>
     */
    default HttpResponse mapHeaders(Function<? super ResponseHeaders, ? extends ResponseHeaders> function) {
        requireNonNull(function, "function");
        final StreamMessage<HttpObject> stream = map(obj -> {
            if (obj instanceof ResponseHeaders) {
                final ResponseHeaders headers = (ResponseHeaders) obj;
                if (!headers.status().isInformational()) {
                    return function.apply(headers);
                }
            }
            return obj;
        });
        return of(stream);
    }

    /**
     * Transforms the {@link HttpData}s emitted by this {@link HttpResponse} by applying the specified
     * {@link Function}.
     *
     * <p>For example:<pre>{@code
     * HttpResponse response = HttpResponse.of("data1,data2");
     * HttpResponse transformed = response.mapData(data -> {
     *     return HttpData.ofUtf8(data.toStringUtf8().replaceAll(",", "\n"));
     * });
     * assert transformed.aggregate().join().contentUtf8().equals("data1\ndata2");
     * }</pre>
     */
    default HttpResponse mapData(Function<? super HttpData, ? extends HttpData> function) {
        requireNonNull(function, "function");
        final StreamMessage<HttpObject> stream =
                map(obj -> obj instanceof HttpData ? function.apply((HttpData) obj) : obj);
        return of(stream);
    }

    /**
     * Transforms the {@linkplain HttpHeaders trailers} emitted by this {@link HttpResponse} by applying the
     * specified {@link Function}.
     *
     * <p>For example:<pre>{@code
     * HttpResponse response = HttpResponse.of("data");
     * HttpResponse transformed = response.mapTrailers(trailers -> {
     *     return trailers.withMutations(builder -> builder.add("trailer1", "foo"));
     * });
     * assert transformed.aggregate().join().trailers().get("trailer1").equals("foo");
     * }</pre>
     */
    default HttpResponse mapTrailers(Function<? super HttpHeaders, ? extends HttpHeaders> function) {
        requireNonNull(function, "function");
        final StreamMessage<HttpObject> stream = map(obj -> {
            if (obj instanceof HttpHeaders && !(obj instanceof ResponseHeaders)) {
                return function.apply((HttpHeaders) obj);
            }
            return obj;
        });
        return of(stream);
    }

    /**
     * Transforms an error emitted by this {@link HttpResponse} by applying the specified {@link Function}.
     *
     * <p>For example:<pre>{@code
     * HttpResponse response = HttpResponse.ofFailure(new IllegalStateException("Something went wrong.");
     * HttpResponse transformed = response.mapError(cause -> {
     *     if (cause instanceof IllegalStateException) {
     *         return new MyDomainException(cause);
     *     } else {
     *         return cause;
     *     }
     * });
     * }</pre>
     */
    @Override
    default HttpResponse mapError(Function<? super Throwable, ? extends Throwable> function) {
        requireNonNull(function, "function");
        return of(HttpMessage.super.mapError(function));
    }

    /**
     * Applies specified {@link Consumer} to the {@link ResponseHeaders} emitted by this {@link HttpResponse}.
     *
     * <p>For example:<pre>{@code
     * HttpResponse response = HttpResponse.of(ResponseHeaders.of(HttpStatus.OK));
     * HttpResponse result = response.peekHeaders(headers -> {
     *      assert headers.status().equals(HttpStatus.OK);
     * });
     * }</pre>
     */
    default HttpResponse peekHeaders(Consumer<? super ResponseHeaders> action) {
        requireNonNull(action, "action");
        final StreamMessage<HttpObject> stream = peek(obj -> action.accept((ResponseHeaders) obj),
                                                      ResponseHeaders.class);
        return of(stream);
    }

    /**
     * Recovers a failed {@link HttpResponse} by switching to a returned fallback {@link HttpResponse}
     * when any error occurs before a {@link ResponseHeaders} is written.
     * Note that the failed {@link HttpResponse} cannot be recovered from an error if a {@link ResponseHeaders}
     * was written already.
     *
     * <p>Example:<pre>{@code
     * HttpResponse response = HttpResponse.ofFailure(new IllegalStateException("Oops..."));
     * // The failed HttpResponse will be recovered by the fallback function.
     * HttpResponse recovered = response.recover(cause -> HttpResponse.of("Fallback"));
     * assert recovered.aggregate().join().contentUtf8().equals("Fallback");
     *
     * // As HTTP headers and body were written already before an error occurred,
     * // the fallback function could not be applied for the failed HttpResponse.
     * HttpResponseWriter response = HttpResponse.streaming();
     * response.write(ResponseHeaders.of(HttpStatus.OK));
     * response.write(HttpData.ofUtf8("Hello"));
     * response.close(new IllegalStateException("Oops..."));
     * HttpResponse notRecovered = response.recover(cause -> HttpResponse.of("Fallback"));
     * // The IllegalStateException will be raised even though a fallback function was added.
     * notRecovered.aggregate().join();
     * }</pre>
     */
    default HttpResponse recover(Function<? super Throwable, ? extends HttpResponse> function) {
        requireNonNull(function, "function");
        return of(new RecoverableStreamMessage<>(this, function, /* allowResuming */ false));
    }
}
