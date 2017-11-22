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

import static java.util.Objects.requireNonNull;

import java.util.Formatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import org.reactivestreams.Publisher;

import com.google.common.base.Throwables;

import com.linecorp.armeria.common.stream.StreamMessage;

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
        final DefaultHttpResponse res = new DefaultHttpResponse();
        res.respond(statusCode);
        return res;
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus} and closes the stream if the
     * {@link HttpStatusClass} is not {@linkplain HttpStatusClass#INFORMATIONAL informational} (1xx).
     */
    static HttpResponse of(HttpStatus status) {
        final DefaultHttpResponse res = new DefaultHttpResponse();
        res.respond(status);
        return res;
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, String content) {
        final DefaultHttpResponse res = new DefaultHttpResponse();
        res.respond(status, mediaType, content);
        return res;
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
        final DefaultHttpResponse res = new DefaultHttpResponse();
        res.respond(status, mediaType, format, args);
        return res;
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, byte[] content) {
        final DefaultHttpResponse res = new DefaultHttpResponse();
        res.respond(status, mediaType, content);
        return res;
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
        final DefaultHttpResponse res = new DefaultHttpResponse();
        res.respond(status, mediaType, content, offset, length);
        return res;
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, HttpData content) {
        final DefaultHttpResponse res = new DefaultHttpResponse();
        res.respond(status, mediaType, content);
        return res;
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
        final DefaultHttpResponse res = new DefaultHttpResponse();
        res.respond(status, mediaType, content, trailingHeaders);
        return res;
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
    default CompletableFuture<AggregatedHttpMessage> aggregate(Executor executor) {
        final CompletableFuture<AggregatedHttpMessage> future = new CompletableFuture<>();
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future);
        completionFuture().whenCompleteAsync(aggregator, executor);
        subscribe(aggregator, executor);
        return future;
    }
}
