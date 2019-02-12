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

import static com.linecorp.armeria.internal.ArmeriaHttpUtil.isContentAlwaysEmpty;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.setOrRemoveContentLength;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.FixedHttpResponse.OneElementFixedHttpResponse;
import com.linecorp.armeria.common.FixedHttpResponse.RegularFixedHttpResponse;
import com.linecorp.armeria.common.FixedHttpResponse.TwoElementFixedHttpResponse;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;

/**
 * A streamed HTTP/2 {@link Response}.
 */
public interface HttpResponse extends Response, StreamMessage<HttpObject> {

    // Note: Ensure we provide the same set of `of()` methods with the `of()` methods of
    //       AggregatedHttpMessage for consistency.

    /**
     * Creates a new HTTP response that can stream an arbitrary number of {@link HttpObject} to the client.
     * The first object written must be of type {@link HttpHeaders}.
     */
    static HttpResponseWriter streaming() {
        return new DefaultHttpResponse();
    }

    /**
     * Creates a new HTTP response that delegates to the {@link HttpResponse} produced by the specified
     * {@link CompletionStage}. If the specified {@link CompletionStage} fails, the returned response will be
     * closed with the same cause as well.
     */
    static HttpResponse from(CompletionStage<? extends HttpResponse> stage) {
        requireNonNull(stage, "stage");
        final DeferredHttpResponse res = new DeferredHttpResponse();
        stage.handle((delegate, thrown) -> {
            if (thrown != null) {
                res.close(Exceptions.peel(thrown));
            } else if (delegate == null) {
                res.close(new NullPointerException("delegate stage produced a null response: " + stage));
            } else {
                res.delegate(delegate);
            }
            return null;
        });
        return res;
    }

    /**
     * Creates a new HTTP response of the specified {@code statusCode} and closes the stream if the
     * {@link HttpStatusClass} is not {@linkplain HttpStatusClass#INFORMATIONAL informational} (1xx).
     */
    static HttpResponse of(int statusCode) {
        return of(HttpStatus.valueOf(statusCode));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus} and closes the stream if the
     * {@link HttpStatusClass} is not {@linkplain HttpStatusClass#INFORMATIONAL informational} (1xx).
     */
    static HttpResponse of(HttpStatus status) {
        requireNonNull(status, "status");
        if (status.codeClass() == HttpStatusClass.INFORMATIONAL) {
            final HttpResponseWriter res = streaming();
            res.write(HttpHeaders.of(status));
            return res;
        } else if (isContentAlwaysEmpty(status)) {
            return new OneElementFixedHttpResponse(HttpHeaders.of(status));
        } else {
            return of(status, MediaType.PLAIN_TEXT_UTF_8, status.toHttpData());
        }
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, CharSequence content) {
        if (content instanceof String) {
            return of(status, mediaType, (String) content);
        }

        requireNonNull(mediaType, "mediaType");
        return of(status, mediaType,
                  HttpData.of(mediaType.charset().orElse(StandardCharsets.UTF_8), content));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, String content) {
        requireNonNull(mediaType, "mediaType");
        return of(status, mediaType,
                  HttpData.of(mediaType.charset().orElse(StandardCharsets.UTF_8), content));
    }

    /**
     * Creates a new HTTP response of OK status with the content as UTF_8 and closes the stream.
     *
     * @param content the content of the response
     */
    static HttpResponse of(String content) {
        return of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, content);
    }

    /**
     * Creates a new HTTP response of OK status with the content as UTF_8 and closes the stream.
     * The content of the response is formatted by {@link String#format(Locale, String, Object...)} with
     * {@linkplain Locale#ENGLISH English locale}.
     *
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     */
    static HttpResponse of(String format, Object... args) {
        return of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, format, args);
    }

    /**
     * Creates a new HTTP response of OK status with the content and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static HttpResponse of(MediaType mediaType, String content) {
        return of(HttpStatus.OK, mediaType, content);
    }

    /**
     * Creates a new HTTP response of OK status with the content and closes the stream.
     * The content of the response is formatted by {@link String#format(Locale, String, Object...)} with
     * {@linkplain Locale#ENGLISH English locale}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     */
    static HttpResponse of(MediaType mediaType, String format, Object... args) {
        return of(HttpStatus.OK, mediaType, format, args);
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus} and closes the stream.
     * The content of the response is formatted by {@link String#format(Locale, String, Object...)} with
     * {@linkplain Locale#ENGLISH English locale}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, String format, Object... args) {
        requireNonNull(mediaType, "mediaType");
        return of(status, mediaType,
                  HttpData.of(mediaType.charset().orElse(StandardCharsets.UTF_8), format, args));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, byte[] content) {
        return of(status, mediaType, HttpData.of(content));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     * @param offset the start offset of {@code content}
     * @param length the length of {@code content}
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, byte[] content, int offset, int length) {
        return of(status, mediaType, HttpData.of(content, offset, length));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, HttpData content) {
        return of(status, mediaType, content, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     * @param trailingHeaders the trailing HTTP headers
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, HttpData content,
                           HttpHeaders trailingHeaders) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");

        final HttpHeaders headers = HttpHeaders.of(status).contentType(mediaType);
        return of(headers, content, trailingHeaders);
    }

    /**
     * Creates a new HTTP response of the specified headers.
     */
    static HttpResponse of(HttpHeaders headers) {
        return of(headers, HttpData.EMPTY_DATA);
    }

    /**
     * Creates a new HTTP response of the specified headers and content.
     */
    static HttpResponse of(HttpHeaders headers, HttpData content) {
        return of(headers, content, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new HTTP response of the specified objects and closes the stream.
     */
    static HttpResponse of(HttpHeaders headers, HttpData content, HttpHeaders trailingHeaders) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        requireNonNull(trailingHeaders, "trailingHeaders");

        final HttpStatus status = headers.status();

        // From the section 8.1.2.4 of RFC 7540:
        //// For HTTP/2 responses, a single :status pseudo-header field is defined that carries the HTTP status
        //// code field (see [RFC7231], Section 6). This pseudo-header field MUST be included in all responses;
        //// otherwise, the response is malformed (Section 8.1.2.6).
        if (status == null) {
            throw new IllegalStateException("not a response (missing :status)");
        }

        final HttpHeaders newHeaders = setOrRemoveContentLength(headers, content, trailingHeaders);
        if (content.isEmpty() && trailingHeaders.isEmpty()) {
            ReferenceCountUtil.safeRelease(content);
            return new OneElementFixedHttpResponse(newHeaders);
        }

        if (!content.isEmpty()) {
            if (trailingHeaders.isEmpty()) {
                return new TwoElementFixedHttpResponse(newHeaders, content);
            } else {
                return new RegularFixedHttpResponse(newHeaders, content, trailingHeaders);
            }
        }

        return new TwoElementFixedHttpResponse(newHeaders, trailingHeaders);
    }

    /**
     * Creates a new HTTP response of the specified objects and closes the stream.
     */
    static HttpResponse of(HttpObject... objs) {
        requireNonNull(objs, "objs");
        for (int i = 0; i < objs.length; i++) {
            if (objs[i] == null) {
                throw new NullPointerException("objs[" + i + "] is null");
            }
        }
        return new RegularFixedHttpResponse(objs);
    }

    /**
     * Converts the {@link AggregatedHttpMessage} into a new complete {@link HttpResponse}.
     */
    static HttpResponse of(AggregatedHttpMessage res) {
        requireNonNull(res, "res");

        final List<HttpHeaders> informationals = res.informationals();
        final HttpHeaders headers = res.headers();
        final HttpData content = res.content();
        final HttpHeaders trailingHeaders = res.trailingHeaders();

        if (informationals.isEmpty()) {
            return of(headers, content, trailingHeaders);
        }

        final int numObjects = informationals.size() +
                               1 /* headers */ +
                               (!content.isEmpty() ? 1 : 0) +
                               (!trailingHeaders.isEmpty() ? 1 : 0);
        final HttpObject[] objs = new HttpObject[numObjects];
        int writerIndex = 0;
        for (HttpHeaders informational : informationals) {
            objs[writerIndex++] = informational;
        }
        objs[writerIndex++] = headers;
        if (!content.isEmpty()) {
            objs[writerIndex++] = content;
        }
        if (!trailingHeaders.isEmpty()) {
            objs[writerIndex] = trailingHeaders;
        }
        return new RegularFixedHttpResponse(objs);
    }

    /**
     * Creates a new HTTP response whose stream is produced from an existing {@link Publisher}.
     */
    static HttpResponse of(Publisher<? extends HttpObject> publisher) {
        return new PublisherBasedHttpResponse(publisher);
    }

    /**
     * Creates a new failed HTTP response.
     */
    static HttpResponse ofFailure(Throwable cause) {
        final HttpResponseWriter res = streaming();
        res.close(cause);
        return res;
    }

    /**
     * Creates a new failed HTTP response.
     *
     * @deprecated Use {@link #ofFailure(Throwable)}.
     */
    @Deprecated
    static HttpResponse ofFailed(Throwable cause) {
        return ofFailure(cause);
    }

    @Override
    default CompletableFuture<Void> closeFuture() {
        return completionFuture();
    }

    @Override
    CompletableFuture<Void> completionFuture();

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the response are received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate() {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future, null);
        completionFuture().handle(aggregator);
        subscribe(aggregator);
        return future;
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the response are received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate(EventExecutor executor) {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future, null);
        completionFuture().handleAsync(aggregator, executor);
        subscribe(aggregator, executor);
        return future;
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the response are received fully. {@link AggregatedHttpMessage#content()} will
     * return a pooled object, and the caller must ensure to release it. If you don't know what this means,
     * use {@link #aggregate()}.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregateWithPooledObjects(ByteBufAllocator alloc) {
        requireNonNull(alloc, "alloc");
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future, alloc);
        completionFuture().handle(aggregator);
        subscribe(aggregator, true);
        return future;
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the request is received fully. {@link AggregatedHttpMessage#content()} will
     * return a pooled object, and the caller must ensure to release it. If you don't know what this means,
     * use {@link #aggregate()}.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregateWithPooledObjects(
            EventExecutor executor, ByteBufAllocator alloc) {
        requireNonNull(executor, "executor");
        requireNonNull(alloc, "alloc");
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future, alloc);
        completionFuture().handleAsync(aggregator, executor);
        subscribe(aggregator, executor, true);
        return future;
    }
}
