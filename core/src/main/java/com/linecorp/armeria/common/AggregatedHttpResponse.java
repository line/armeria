/*
 * Copyright 2019 LINE Corporation
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
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.maybeUpdateContentLengthAndEndOfStream;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.FixedHttpResponse.RegularFixedHttpResponse;

/**
 * A complete HTTP response whose content is readily available as a single {@link HttpData}.
 */
public interface AggregatedHttpResponse extends AggregatedHttpMessage {

    // Note: Ensure we provide the same set of `of()` methods with the `of()` and `respond()` methods of
    //       HttpResponse and HttpResponseWriter for consistency.

    /**
     * Creates a new HTTP response.
     *
     * @param statusCode the HTTP status code
     *
     * @throws IllegalArgumentException if the specified {@code statusCode} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static AggregatedHttpResponse of(int statusCode) {
        return of(HttpStatus.valueOf(statusCode));
    }

    /**
     * Creates a new HTTP response.
     *
     * @param status the HTTP status
     *
     * @throws IllegalArgumentException if the specified {@link HttpStatus} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static AggregatedHttpResponse of(HttpStatus status) {
        requireNonNull(status, "status");
        checkArgument(!status.isInformational(), "status: %s (expected: a non-1xx status)", status);
        if (status.isContentAlwaysEmpty()) {
            return of(ResponseHeaders.of(status));
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
    static AggregatedHttpResponse of(HttpStatus status, MediaType mediaType, CharSequence content) {
        requireNonNull(status, "status");
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
    static AggregatedHttpResponse of(HttpStatus status, MediaType mediaType, String content) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(status, mediaType,
                  HttpData.of(mediaType.charset(StandardCharsets.UTF_8), content));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}. The content of the response is
     * formatted by {@link String#format(Locale, String, Object...)} with
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
    static AggregatedHttpResponse of(HttpStatus status, MediaType mediaType,
                                     @FormatString String format, Object... args) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        return of(status,
                  mediaType,
                  String.format(Locale.ENGLISH, format, args).getBytes(
                          mediaType.charset(StandardCharsets.UTF_8)));
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
    static AggregatedHttpResponse of(HttpStatus status, MediaType mediaType, byte[] content) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
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
    static AggregatedHttpResponse of(HttpStatus status, MediaType mediaType, HttpData content) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
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
    static AggregatedHttpResponse of(HttpStatus status, MediaType mediaType, HttpData content,
                                     HttpHeaders trailers) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        requireNonNull(trailers, "trailers");

        final ResponseHeaders headers = ResponseHeaders.builder(status)
                                                       .contentType(mediaType)
                                                       .build();
        return of(headers, content, trailers);
    }

    /**
     * Creates a new HTTP response with empty content.
     *
     * @param headers the HTTP headers
     *
     * @throws IllegalArgumentException if the status of the specified {@link ResponseHeaders} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static AggregatedHttpResponse of(ResponseHeaders headers) {
        requireNonNull(headers, "headers");
        return of(headers, HttpData.empty(), HttpHeaders.of());
    }

    /**
     * Creates a new HTTP response.
     *
     * @param headers the HTTP headers
     * @param content the content of the HTTP response
     *
     * @throws IllegalArgumentException if the status of the specified {@link ResponseHeaders} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static AggregatedHttpResponse of(ResponseHeaders headers, HttpData content) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        return of(headers, content, HttpHeaders.of());
    }

    /**
     * Creates a new HTTP response.
     *
     * @param headers the HTTP headers
     * @param content the content of the HTTP response
     * @param trailers the HTTP trailers
     *
     * @throws IllegalArgumentException if the status of the specified {@link ResponseHeaders} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static AggregatedHttpResponse of(ResponseHeaders headers, HttpData content, HttpHeaders trailers) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        requireNonNull(trailers, "trailers");
        return of(ImmutableList.of(), headers, content, trailers);
    }

    /**
     * Creates a new HTTP response.
     *
     * @param informationals the informational class (1xx) HTTP headers
     * @param headers the HTTP headers
     * @param content the content of the HTTP response
     * @param trailers the HTTP trailers
     *
     * @throws IllegalArgumentException if the status of the specified {@link ResponseHeaders} is
     *                                  {@linkplain HttpStatus#isInformational() informational}.
     */
    static AggregatedHttpResponse of(Iterable<ResponseHeaders> informationals, ResponseHeaders headers,
                                     HttpData content, HttpHeaders trailers) {

        requireNonNull(informationals, "informationals");
        requireNonNull(headers, "headers");
        final HttpStatus status = headers.status();
        checkArgument(!status.isInformational(), "status: %s (expected: a non-1xx status)", status);

        requireNonNull(content, "content");
        requireNonNull(trailers, "trailers");

        // Set the 'content-length' header if possible.
        final ResponseHeaders newHeaders =
                maybeUpdateContentLengthAndEndOfStream(headers, content, trailers, true);

        return new DefaultAggregatedHttpResponse(ImmutableList.copyOf(informationals),
                                                 newHeaders, content, trailers);
    }

    /**
     * Returns the {@link ResponseHeaders}.
     */
    @Override
    ResponseHeaders headers();

    /**
     * Returns the informational class (1xx) HTTP headers.
     */
    List<ResponseHeaders> informationals();

    /**
     * Returns the {@linkplain HttpHeaderNames#STATUS STATUS} of this response.
     */
    HttpStatus status();

    /**
     * Converts this response into a new complete {@link HttpResponse}.
     *
     * @return the new {@link HttpResponse} converted from this response.
     */
    default HttpResponse toHttpResponse() {
        final List<ResponseHeaders> informationals = informationals();
        final ResponseHeaders headers = headers();
        final HttpData content = content();
        final HttpHeaders trailers = trailers();

        if (informationals.isEmpty()) {
            return HttpResponse.of(headers, content, trailers);
        }

        final int numObjects = informationals.size() +
                               1 /* headers */ +
                               (!content.isEmpty() ? 1 : 0) +
                               (!trailers.isEmpty() ? 1 : 0);
        final HttpObject[] objs = new HttpObject[numObjects];
        int writerIndex = 0;
        for (ResponseHeaders informational : informationals) {
            objs[writerIndex++] = informational;
        }
        objs[writerIndex++] = headers;
        if (!content.isEmpty()) {
            objs[writerIndex++] = content;
        }
        if (!trailers.isEmpty()) {
            objs[writerIndex] = trailers;
        }
        return new RegularFixedHttpResponse(objs);
    }
}
