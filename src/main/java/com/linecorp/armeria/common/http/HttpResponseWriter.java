/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.http;

import static com.linecorp.armeria.internal.http.ArmeriaHttpUtil.isContentAlwaysEmpty;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.google.common.net.MediaType;

import com.linecorp.armeria.common.reactivestreams.Writer;

public interface HttpResponseWriter extends Writer<HttpObject> {
    // TODO(trustin): Add lots of convenience methods for easier response construction.

    default void respond(int statusCode) {
        respond(HttpStatus.valueOf(statusCode));
    }

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

    default void respond(HttpStatus status, MediaType mediaType, String content) {
        requireNonNull(status, "status");
        requireNonNull(content, "content");
        requireNonNull(mediaType, "mediaType");
        respond(status,
                mediaType, content.getBytes(mediaType.charset().or(StandardCharsets.UTF_8)));
    }

    default void respond(HttpStatus status, MediaType mediaType, String format, Object... args) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        respond(status,
                mediaType,
                String.format(Locale.ENGLISH, format, args).getBytes(
                        mediaType.charset().or(StandardCharsets.UTF_8)));
    }

    default void respond(HttpStatus status, MediaType mediaType, byte[] content) {
        requireNonNull(content, "content");
        respond(status, mediaType, HttpData.of(content));
    }

    default void respond(HttpStatus status, MediaType mediaType, byte[] content, int offset, int length) {
        requireNonNull(content, "content");
        respond(status, mediaType, HttpData.of(content, offset, length));
    }

    default void respond(HttpStatus status, MediaType mediaType, HttpData content) {
        requireNonNull(status, "status");
        requireNonNull(content, "content");
        requireNonNull(mediaType, "mediaType");

        final int length = content.length();
        final HttpHeaders headers =
                HttpHeaders.of(status)
                           .set(HttpHeaderNames.CONTENT_TYPE, mediaType.toString())
                           .setInt(HttpHeaderNames.CONTENT_LENGTH, length);

        if (isContentAlwaysEmpty(status)) {
            if (length != 0) {
                throw new IllegalArgumentException(
                        "A " + status + " response must have empty content: " + length + " byte(s)");
            }
            write(headers);
        } else {
            write(headers);
            write(content);
        }

        close();
    }

    default void respond(AggregatedHttpMessage res) {
        final HttpHeaders headers = res.headers();
        write(headers);

        final HttpData content = res.content();
        if (isContentAlwaysEmpty(headers.status())) {
            if (!content.isEmpty()) {
                throw new IllegalArgumentException(
                        "A " + headers.status() + " response must have empty content: " +
                        content.length() + " byte(s)");
            }

            if (!res.trailingHeaders().isEmpty()) {
                throw new IllegalArgumentException(
                        "A " + headers.status() + " response must not have trailing headers: " +
                        res.trailingHeaders());
            }

            close();
            return;
        }

        // Add content if not empty.
        if (!content.isEmpty()) {
            write(content);
        }

        // Add trailing headers if not empty.
        final HttpHeaders trailingHeaders = res.trailingHeaders();
        if (!trailingHeaders.isEmpty()) {
            write(trailingHeaders);
        }

        close();
    }
}
