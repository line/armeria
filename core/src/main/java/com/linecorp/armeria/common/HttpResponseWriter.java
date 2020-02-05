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

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.isContentAlwaysEmptyWithValidation;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Formatter;
import java.util.Locale;

import com.linecorp.armeria.common.stream.StreamWriter;

import io.netty.util.ReferenceCountUtil;

/**
 * An {@link HttpResponse} that can have {@link HttpObject}s written to it.
 */
public interface HttpResponseWriter extends HttpResponse, StreamWriter<HttpObject> {

    /**
     * Writes the HTTP response of the specified {@code statusCode} and closes the stream if the
     * {@link HttpStatusClass} is not {@linkplain HttpStatusClass#INFORMATIONAL informational} (1xx).
     *
     * @deprecated Use {@link HttpResponse#of(int)}.
     */
    @Deprecated
    default void respond(int statusCode) {
        respond(HttpStatus.valueOf(statusCode));
    }

    /**
     * Writes the HTTP response of the specified {@link HttpStatus} and closes the stream if the
     * {@link HttpStatusClass} is not {@linkplain HttpStatusClass#INFORMATIONAL informational} (1xx).
     *
     * @deprecated Use {@link HttpResponse#of(HttpStatus)}.
     */
    @Deprecated
    default void respond(HttpStatus status) {
        requireNonNull(status, "status");
        if (status.codeClass() == HttpStatusClass.INFORMATIONAL) {
            tryWrite(ResponseHeaders.of(status));
        } else if (status.isContentAlwaysEmpty()) {
            if (tryWrite(ResponseHeaders.of(status))) {
                close();
            }
        } else {
            respond(status, MediaType.PLAIN_TEXT_UTF_8, status.toHttpData());
        }
    }

    /**
     * Writes the HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     *
     * @deprecated Use {@link HttpResponse#of(HttpStatus, MediaType, String)}.
     */
    @Deprecated
    default void respond(HttpStatus status, MediaType mediaType, String content) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        respond(status,
                mediaType, content.getBytes(mediaType.charset(StandardCharsets.UTF_8)));
    }

    /**
     * Writes the HTTP response of the specified {@link HttpStatus} and closes the stream.
     * The content of the response is formatted by {@link String#format(Locale, String, Object...)} with
     * {@linkplain Locale#ENGLISH English locale}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     *
     * @deprecated Use {@link HttpResponse#of(HttpStatus, MediaType, String, Object...)}.
     */
    @Deprecated
    default void respond(HttpStatus status, MediaType mediaType, String format, Object... args) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        respond(status,
                mediaType,
                String.format(Locale.ENGLISH, format, args).getBytes(
                        mediaType.charset(StandardCharsets.UTF_8)));
    }

    /**
     * Writes the HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     *
     * @deprecated Use {@link HttpResponse#of(HttpStatus, MediaType, byte[])}.
     */
    @Deprecated
    default void respond(HttpStatus status, MediaType mediaType, byte[] content) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        respond(status, mediaType, HttpData.wrap(content));
    }

    /**
     * Writes the HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     *
     * @deprecated Use {@link HttpResponse#of(HttpStatus, MediaType, HttpData)}.
     */
    @Deprecated
    default void respond(HttpStatus status, MediaType mediaType, HttpData content) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        respond(status, mediaType, content, HttpHeaders.of());
    }

    /**
     * Writes the HTTP response of the specified {@link HttpStatus} and closes the stream.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     * @param trailers the HTTP trailers
     *
     * @deprecated Use {@link HttpResponse#of(HttpStatus, MediaType, HttpData, HttpHeaders)}.
     */
    @Deprecated
    default void respond(HttpStatus status, MediaType mediaType, HttpData content, HttpHeaders trailers) {
        boolean transferredContent = false;
        try {
            requireNonNull(status, "status");
            requireNonNull(mediaType, "mediaType");
            requireNonNull(content, "content");
            requireNonNull(trailers, "trailers");

            final boolean contentAlwaysEmpty = isContentAlwaysEmptyWithValidation(status, content, trailers);
            final ResponseHeaders headers =
                    ResponseHeaders.of(status,
                                       HttpHeaderNames.CONTENT_TYPE, mediaType,
                                       HttpHeaderNames.CONTENT_LENGTH, content.length());

            if (!tryWrite(headers)) {
                return;
            }

            // Add content if not empty.
            if (!contentAlwaysEmpty && !content.isEmpty()) {
                transferredContent = true;
                if (!tryWrite(content)) {
                    return;
                }
            }

            // Add trailers if not empty.
            if (!trailers.isEmpty()) {
                tryWrite(trailers);
            }
        } finally {
            close();
            if (!transferredContent) {
                ReferenceCountUtil.release(content);
            }
        }
    }

    /**
     * Writes the specified HTTP response and closes the stream.
     */
    default void close(AggregatedHttpResponse res) {
        boolean transferredContent = false;
        HttpData content = null;
        try {
            requireNonNull(res, "res");

            final ResponseHeaders headers = res.headers();
            final HttpStatus status = headers.status();
            content = res.content();
            final HttpHeaders trailers = res.trailers();
            final boolean contentAlwaysEmpty = isContentAlwaysEmptyWithValidation(status, content, trailers);

            if (!tryWrite(headers)) {
                return;
            }

            // Add content if not empty.
            if (!contentAlwaysEmpty && !content.isEmpty()) {
                transferredContent = true;
                if (!tryWrite(content)) {
                    return;
                }
            }

            // Add trailers if not empty.
            if (!trailers.isEmpty()) {
                tryWrite(trailers);
            }
        } finally {
            close();
            if (!transferredContent) {
                ReferenceCountUtil.release(content);
            }
        }
    }
}
