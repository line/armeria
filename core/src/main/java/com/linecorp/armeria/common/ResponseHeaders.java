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
import static java.util.Objects.requireNonNull;

import java.util.function.Consumer;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Immutable HTTP/2 headers for an {@link HttpResponse}.
 *
 * @see HttpHeaders
 * @see RequestHeaders
 */
@JsonDeserialize(using = ResponseHeadersJsonDeserializer.class)
public interface ResponseHeaders extends HttpHeaders, ResponseHeaderGetters {

    /**
     * Returns a new empty builder.
     */
    static ResponseHeadersBuilder builder() {
        return new DefaultResponseHeadersBuilder();
    }

    /**
     * Returns a new builder with the specified {@code statusCode}.
     */
    static ResponseHeadersBuilder builder(int statusCode) {
        return builder(HttpStatus.valueOf(statusCode));
    }

    /**
     * Returns a new builder with the specified {@link HttpStatus}.
     */
    static ResponseHeadersBuilder builder(HttpStatus status) {
        requireNonNull(status, "status");
        return builder().status(status);
    }

    /**
     * Returns a new {@link ResponseHeaders} with the specified {@code statusCode}.
     */
    static ResponseHeaders of(int statusCode) {
        return builder(HttpStatus.valueOf(statusCode)).build();
    }

    /**
     * Returns a new {@link ResponseHeaders} with the specified {@link HttpStatus}.
     */
    static ResponseHeaders of(HttpStatus status) {
        return builder(status).build();
    }

    /**
     * Returns a new {@link ResponseHeaders} with the specified {@link HttpStatus} and an additional header.
     */
    static ResponseHeaders of(HttpStatus status, CharSequence name, String value) {
        return builder(status).add(name, value).build();
    }

    /**
     * Returns a new {@link ResponseHeaders} with the specified {@link HttpStatus} and an additional header.
     * The value is converted into a {@link String} as explained in
     * <a href="HttpHeaders.html#object-values">Specifying a non-String header value</a>.
     */
    static ResponseHeaders of(HttpStatus status, CharSequence name, Object value) {
        return builder(status).addObject(name, value).build();
    }

    /**
     * Returns a new {@link ResponseHeaders} with the specified {@link HttpStatus} and additional headers.
     */
    static ResponseHeaders of(HttpStatus status,
                              CharSequence name1, String value1,
                              CharSequence name2, String value2) {
        return builder(status).add(name1, value1)
                              .add(name2, value2)
                              .build();
    }

    /**
     * Returns a new {@link ResponseHeaders} with the specified {@link HttpStatus} and additional headers.
     * The values are converted into {@link String}s as explained in
     * <a href="HttpHeaders.html#object-values">Specifying a non-String header value</a>.
     */
    static ResponseHeaders of(HttpStatus status,
                              CharSequence name1, Object value1,
                              CharSequence name2, Object value2) {
        return builder(status).addObject(name1, value1)
                              .addObject(name2, value2)
                              .build();
    }

    /**
     * Returns a new {@link ResponseHeaders} with the specified {@link HttpStatus} and additional headers.
     */
    static ResponseHeaders of(HttpStatus status,
                              CharSequence name1, String value1,
                              CharSequence name2, String value2,
                              CharSequence name3, String value3) {
        return builder(status).add(name1, value1)
                              .add(name2, value2)
                              .add(name3, value3)
                              .build();
    }

    /**
     * Returns a new {@link ResponseHeaders} with the specified {@link HttpStatus} and additional headers.
     * The values are converted into {@link String}s as explained in
     * <a href="HttpHeaders.html#object-values">Specifying a non-String header value</a>.
     */
    static ResponseHeaders of(HttpStatus status,
                              CharSequence name1, Object value1,
                              CharSequence name2, Object value2,
                              CharSequence name3, Object value3) {
        return builder(status).addObject(name1, value1)
                              .addObject(name2, value2)
                              .addObject(name3, value3)
                              .build();
    }

    /**
     * Returns a new {@link ResponseHeaders} with the specified {@link HttpStatus} and additional headers.
     */
    static ResponseHeaders of(HttpStatus status,
                              CharSequence name1, String value1,
                              CharSequence name2, String value2,
                              CharSequence name3, String value3,
                              CharSequence name4, String value4) {
        return builder(status).add(name1, value1)
                              .add(name2, value2)
                              .add(name3, value3)
                              .add(name4, value4)
                              .build();
    }

    /**
     * Returns a new {@link ResponseHeaders} with the specified {@link HttpStatus} and additional headers.
     * The values are converted into {@link String}s as explained in
     * <a href="HttpHeaders.html#object-values">Specifying a non-String header value</a>.
     */
    static ResponseHeaders of(HttpStatus status,
                              CharSequence name1, Object value1,
                              CharSequence name2, Object value2,
                              CharSequence name3, Object value3,
                              CharSequence name4, Object value4) {
        return builder(status).addObject(name1, value1)
                              .addObject(name2, value2)
                              .addObject(name3, value3)
                              .addObject(name4, value4)
                              .build();
    }

    /**
     * Returns a new {@link ResponseHeaders} copied from the specified {@link HttpHeaders}.
     *
     * @throws IllegalArgumentException if the specified {@link HttpHeaders} does not have
     *                                  {@code ":status"} header.
     */
    static ResponseHeaders of(HttpHeaders headers) {
        if (headers instanceof ResponseHeaders) {
            return (ResponseHeaders) headers;
        }

        requireNonNull(headers, "headers");
        // From the section 8.1.2.4 of RFC 7540:
        //// For HTTP/2 responses, a single :status pseudo-header field is defined that carries the HTTP status
        //// code field (see [RFC7231], Section 6). This pseudo-header field MUST be included in all responses;
        //// otherwise, the response is malformed (Section 8.1.2.6).
        checkArgument(headers.contains(HttpHeaderNames.STATUS), ":status header does not exist.");

        if (headers instanceof HttpHeadersBase) {
            return new DefaultResponseHeaders((HttpHeadersBase) headers);
        }

        return new DefaultResponseHeaders(headers);
    }

    @Override
    ResponseHeadersBuilder toBuilder();

    @Override
    default ResponseHeaders withMutations(Consumer<HttpHeadersBuilder> mutator) {
        return (ResponseHeaders) HttpHeaders.super.withMutations(mutator);
    }
}
