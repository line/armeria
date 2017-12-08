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
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.isContentAlwaysEmptyWithValidation;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.reactivestreams.Publisher;

import com.google.common.base.Throwables;

import com.linecorp.armeria.common.FixedHttpResponse.OneElementFixedHttpResponse;
import com.linecorp.armeria.common.FixedHttpResponse.RegularFixedHttpResponse;
import com.linecorp.armeria.common.FixedHttpResponse.TwoElementFixedHttpResponse;
import com.linecorp.armeria.common.stream.StreamMessage;

import io.netty.util.concurrent.EventExecutor;

/**
 * A streamed HTTP/2 {@link Response}.
 */
public interface HttpResponse extends Response, StreamMessage<HttpObject> {

    // Note: Ensure we provide the same set of `of()` methods with the `of()` and `respond()` methods of
    //       HttpResponseWriter and AggregatedHttpMessage for consistency.

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
            DefaultHttpResponse res = new DefaultHttpResponse();
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
    static HttpResponse of(HttpStatus status, MediaType mediaType, String content) {
        return of(status, mediaType, content.getBytes(mediaType.charset().orElse(StandardCharsets.UTF_8)));
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
        return of(status,
                  mediaType,
                  String.format(Locale.ENGLISH, format, args).getBytes(
                          mediaType.charset().orElse(StandardCharsets.UTF_8)));
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

        final HttpHeaders headers =
                HttpHeaders.of(status)
                           .setObject(HttpHeaderNames.CONTENT_TYPE, mediaType)
                           .setInt(HttpHeaderNames.CONTENT_LENGTH, content.length());
        return of(headers, content, trailingHeaders);
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

        if (isContentAlwaysEmptyWithValidation(status, content, trailingHeaders)) {
            return new OneElementFixedHttpResponse(headers);
        } else if (!content.isEmpty()) {
            if (trailingHeaders.isEmpty()) {
                return new TwoElementFixedHttpResponse(headers, content);
            } else {
                return new RegularFixedHttpResponse(headers, content, trailingHeaders);
            }
        } else if (!trailingHeaders.isEmpty()) {
            return new TwoElementFixedHttpResponse(headers, trailingHeaders);
        } else {
            return new OneElementFixedHttpResponse(headers);
        }
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
        final DefaultHttpResponse res = new DefaultHttpResponse();
        res.close(cause);
        return res;
    }

    /**
     * Creates a new failed HTTP response.
     *
     * @deprecated Use {@link #ofFailure(Throwable)} instead.
     */
    @Deprecated
    static HttpResponse ofFailed(Throwable cause) {
        return ofFailure(cause);
    }

    /**
     * Creates a new HTTP response that delegates to the {@link HttpResponse} produced by the specified
     * {@link CompletionStage}. If the specified {@link CompletionStage} fails, the returned response will be
     * closed with the same cause as well.
     */
    static HttpResponse from(CompletionStage<? extends HttpResponse> stage) {
        requireNonNull(stage, "stage");
        final DeferredHttpResponse res = new DeferredHttpResponse();
        stage.whenComplete((delegate, thrown) -> {
            if (thrown != null) {
                res.close(Throwables.getRootCause(thrown));
            } else if (delegate == null) {
                res.close(new NullPointerException("delegate stage produced a null response: " + stage));
            } else {
                res.delegate(delegate);
            }
        });
        return res;
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
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future);
        completionFuture().whenComplete(aggregator);
        subscribe(aggregator);
        return future;
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailing headers of the response are received fully.
     */
    default CompletableFuture<AggregatedHttpMessage> aggregate(EventExecutor executor) {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future);
        completionFuture().whenCompleteAsync(aggregator, executor);
        subscribe(aggregator, executor);
        return future;
    }
}
