/*
 * Copyright 2017 LINE Corporation
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
import java.util.Locale;

import com.linecorp.armeria.common.stream.StreamWriter;

/**
 * A {@link StreamWriter} of an {@link HttpResponse}.
 */
public interface HttpResponseWriter extends StreamWriter<HttpObject> {
    // TODO(trustin): Add lots of convenience methods for easier response construction.

    // Note: Ensure we provide the same set of `respond()` methods with the `of()` methods of
    //       HttpResponse and AggregatedHttpMessage for consistency.

    /**
     * Writes the HTTP response of the specified {@code statusCode} and closes the stream if the
     * {@link HttpStatusClass} is not {@linkplain HttpStatusClass#INFORMATIONAL informational} (1xx).
     */
    default void respond(int statusCode) {
        respond(HttpStatus.valueOf(statusCode));
    }

    /**
     * Writes the HTTP response of the specified {@link HttpStatus} and closes the stream if the
     * {@link HttpStatusClass} is not {@linkplain HttpStatusClass#INFORMATIONAL informational} (1xx).
     */
    default void respond(HttpStatus status) {
        requireNonNull(status, "status");
        if (status.codeClass() == HttpStatusClass.INFORMATIONAL) {
            write(HttpHeaders.of(status));
        } else if (isContentAlwaysEmpty(status)) {
            write(HttpHeaders.of(status));
            close();
        } else {
            respond(status, MediaType.PLAIN_TEXT_UTF_8, status.toHttpData());
        }
    }

    /**
     * Writes the HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    default void respond(HttpStatus status, MediaType mediaType, String content) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        respond(status,
                mediaType, content.getBytes(mediaType.charset().orElse(StandardCharsets.UTF_8)));
    }

    /**
     * Writes the HTTP response of the specified {@link HttpStatus} and closes the stream.
     * The content of the response is formatted by {@link String#format(Locale, String, Object...)} with
     * {@linkplain Locale#ENGLISH English locale}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     */
    default void respond(HttpStatus status, MediaType mediaType, String format, Object... args) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        respond(status,
                mediaType,
                String.format(Locale.ENGLISH, format, args).getBytes(
                        mediaType.charset().orElse(StandardCharsets.UTF_8)));
    }

    /**
     * Writes the HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    default void respond(HttpStatus status, MediaType mediaType, byte[] content) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        respond(status, mediaType, HttpData.of(content));
    }

    /**
     * Writes the HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     * @param offset the start offset of {@code content}
     * @param length the length of {@code content}
     */
    default void respond(HttpStatus status, MediaType mediaType, byte[] content, int offset, int length) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        respond(status, mediaType, HttpData.of(content, offset, length));
    }

    /**
     * Writes the HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    default void respond(HttpStatus status, MediaType mediaType, HttpData content) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        respond(status, mediaType, content, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Writes the HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     * @param trailingHeaders the trailing HTTP headers
     */
    default void respond(HttpStatus status, MediaType mediaType, HttpData content,
                         HttpHeaders trailingHeaders) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        requireNonNull(trailingHeaders, "trailingHeaders");

        final HttpHeaders headers =
                HttpHeaders.of(status)
                           .setObject(HttpHeaderNames.CONTENT_TYPE, mediaType)
                           .setInt(HttpHeaderNames.CONTENT_LENGTH, content.length());

        if (isContentAlwaysEmptyWithValidation(status, content, trailingHeaders)) {
            write(headers);
        } else {
            write(headers);
            // Add content if not empty.
            if (!content.isEmpty()) {
                write(content);
            }
        }

        // Add trailing headers if not empty.
        if (!trailingHeaders.isEmpty()) {
            write(trailingHeaders);
        }

        close();
    }

    /**
     * Writes the specified HTTP response and closes the stream.
     */
    default void respond(AggregatedHttpMessage res) {
        requireNonNull(res, "res");

        final HttpHeaders headers = res.headers();
        final HttpStatus status = headers.status();
        final HttpData content = res.content();
        final HttpHeaders trailingHeaders = res.trailingHeaders();

        if (isContentAlwaysEmptyWithValidation(status, content, trailingHeaders)) {
            write(headers);
        } else {
            write(headers);
            // Add content if not empty.
            if (!content.isEmpty()) {
                write(content);
            }
        }

        // Add trailing headers if not empty.
        if (!trailingHeaders.isEmpty()) {
            write(trailingHeaders);
        }

        close();
    }
}
