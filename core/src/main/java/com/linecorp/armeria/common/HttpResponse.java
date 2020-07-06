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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.FixedHttpResponse.OneElementFixedHttpResponse;
import com.linecorp.armeria.common.FixedHttpResponse.RegularFixedHttpResponse;
import com.linecorp.armeria.common.FixedHttpResponse.TwoElementFixedHttpResponse;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.internal.common.DefaultHttpResponse;
import com.linecorp.armeria.internal.common.HttpResponseAggregator;

import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;

/**
 * A streamed HTTP/2 {@link Response}.
 */
public interface HttpResponse extends Response, StreamMessage<HttpObject> {

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
        return delayed(response, delay, CommonPools.workerGroup().next());
    }

    /**
     * Creates a new HTTP response that delegates to the provided {@link HttpResponse}, beginning publishing
     * after {@code delay} has passed from the provided {@link ScheduledExecutorService}.
     */
    static HttpResponse delayed(HttpResponse response, Duration delay, ScheduledExecutorService executor) {
        requireNonNull(response, "response");
        requireNonNull(delay, "delay");
        requireNonNull(executor, "executor");
        final DeferredHttpResponse res = new DeferredHttpResponse();
        executor.schedule(() -> res.delegate(response), delay.toNanos(), TimeUnit.NANOSECONDS);
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
        return of(status, mediaType,
                  HttpData.of(mediaType.charset(StandardCharsets.UTF_8), format, args));
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

        final ResponseHeaders headers = ResponseHeaders.of(status,
                                                           HttpHeaderNames.CONTENT_TYPE, mediaType);
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
            ReferenceCountUtil.safeRelease(content);
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
            return new RegularFixedHttpResponse(newHeaders, content, trailers);
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
     */
    static HttpResponse of(Publisher<? extends HttpObject> publisher) {
        requireNonNull(publisher, "publisher");
        if (publisher instanceof HttpResponse) {
            return (HttpResponse) publisher;
        } else {
            return new PublisherBasedHttpResponse(publisher);
        }
    }

    /**
     * Creates a new failed HTTP response.
     */
    static HttpResponse ofFailure(Throwable cause) {
        final HttpResponseWriter res = streaming();
        res.close(cause);
        return res;
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
        final CompletableFuture<AggregatedHttpResponse> future = new EventLoopCheckingFuture<>();
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future, null);
        subscribe(aggregator, executor);
        return future;
    }

    /**
     * Returns a new {@link HttpResponseDuplicator} that duplicates this {@link HttpResponse} into one or
     * more {@link HttpResponse}s, which publish the same elements.
     * Note that you cannot subscribe to this {@link HttpResponse} anymore after you call this method.
     * To subscribe, call {@link HttpResponseDuplicator#duplicate()} from the returned
     * {@link HttpResponseDuplicator}.
     */
    @Override
    default HttpResponseDuplicator toDuplicator() {
        return toDuplicator(Flags.defaultMaxResponseLength());
    }

    /**
     * Returns a new {@link HttpResponseDuplicator} that duplicates this {@link HttpResponse} into one or
     * more {@link HttpResponse}s, which publish the same elements.
     * Note that you cannot subscribe to this {@link HttpResponse} anymore after you call this method.
     * To subscribe, call {@link HttpResponseDuplicator#duplicate()} from the returned
     * {@link HttpResponseDuplicator}.
     *
     * @param executor the executor to duplicate
     */
    @Override
    default HttpResponseDuplicator toDuplicator(EventExecutor executor) {
        return toDuplicator(executor, Flags.defaultMaxResponseLength());
    }

    /**
     * Returns a new {@link HttpResponseDuplicator} that duplicates this {@link HttpResponse} into one or
     * more {@link HttpResponse}s, which publish the same elements.
     * Note that you cannot subscribe to this {@link HttpResponse} anymore after you call this method.
     * To subscribe, call {@link HttpResponseDuplicator#duplicate()} from the returned
     * {@link HttpResponseDuplicator}.
     *
     * @param maxResponseLength the maximum response length that the duplicator can hold in its buffer.
     *                         {@link ContentTooLargeException} is raised if the length of the buffered
     *                         {@link HttpData} is greater than this value.
     */
    default HttpResponseDuplicator toDuplicator(long maxResponseLength) {
        return toDuplicator(defaultSubscriberExecutor(), maxResponseLength);
    }

    /**
     * Returns a new {@link HttpResponseDuplicator} that duplicates this {@link HttpResponse} into one or
     * more {@link HttpResponse}s, which publish the same elements.
     * Note that you cannot subscribe to this {@link HttpResponse} anymore after you call this method.
     * To subscribe, call {@link HttpResponseDuplicator#duplicate()} from the returned
     * {@link HttpResponseDuplicator}.
     *
     * @param executor the executor to duplicate
     * @param maxResponseLength the maximum response length that the duplicator can hold in its buffer.
     *                         {@link ContentTooLargeException} is raised if the length of the buffered
     *                         {@link HttpData} is greater than this value.
     */
    default HttpResponseDuplicator toDuplicator(EventExecutor executor, long maxResponseLength) {
        requireNonNull(executor, "executor");
        return new DefaultHttpResponseDuplicator(this, executor, maxResponseLength);
    }
}
