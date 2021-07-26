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
 * Immutable HTTP/2 headers for an {@link HttpRequest}.
 *
 * @see HttpHeaders
 * @see ResponseHeaders
 */
@JsonDeserialize(using = RequestHeadersJsonDeserializer.class)
public interface RequestHeaders extends HttpHeaders, RequestHeaderGetters {

    /**
     * Returns a new empty builder.
     */
    static RequestHeadersBuilder builder() {
        return new DefaultRequestHeadersBuilder();
    }

    /**
     * Returns a new builder with the specified {@link HttpMethod} and {@code path}.
     */
    static RequestHeadersBuilder builder(HttpMethod method, String path) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        return builder().method(method)
                        .add(HttpHeaderNames.PATH, path);
    }

    /**
     * Returns a new {@link RequestHeaders} with the specified {@link HttpMethod} and {@code path}.
     */
    static RequestHeaders of(HttpMethod method, String path) {
        return builder(method, path).build();
    }

    /**
     * Returns a new {@link RequestHeaders} with the specified {@link HttpMethod}, {@code path} and
     * an additional header.
     */
    static RequestHeaders of(HttpMethod method, String path,
                             CharSequence name, String value) {
        return builder(method, path).add(name, value)
                                    .build();
    }

    /**
     * Returns a new {@link RequestHeaders} with the specified {@link HttpMethod}, {@code path} and
     * an additional header. The value is converted into a {@link String} as explained in
     * <a href="HttpHeaders.html#object-values">Specifying a non-String header value</a>.
     */
    static RequestHeaders of(HttpMethod method, String path,
                             CharSequence name, Object value) {
        return builder(method, path).addObject(name, value)
                                    .build();
    }

    /**
     * Returns a new {@link RequestHeaders} with the specified {@link HttpMethod}, {@code path} and
     * additional headers.
     */
    static RequestHeaders of(HttpMethod method, String path,
                             CharSequence name1, String value1,
                             CharSequence name2, String value2) {
        return builder(method, path).add(name1, value1)
                                    .add(name2, value2)
                                    .build();
    }

    /**
     * Returns a new {@link RequestHeaders} with the specified {@link HttpMethod}, {@code path} and
     * additional headers. The values are converted into {@link String}s as explained in
     * <a href="HttpHeaders.html#object-values">Specifying a non-String header value</a>.
     */
    static RequestHeaders of(HttpMethod method, String path,
                             CharSequence name1, Object value1,
                             CharSequence name2, Object value2) {
        return builder(method, path).addObject(name1, value1)
                                    .addObject(name2, value2)
                                    .build();
    }

    /**
     * Returns a new {@link RequestHeaders} with the specified {@link HttpMethod}, {@code path} and
     * additional headers.
     */
    static RequestHeaders of(HttpMethod method, String path,
                             CharSequence name1, String value1,
                             CharSequence name2, String value2,
                             CharSequence name3, String value3) {
        return builder(method, path).add(name1, value1)
                                    .add(name2, value2)
                                    .add(name3, value3)
                                    .build();
    }

    /**
     * Returns a new {@link RequestHeaders} with the specified {@link HttpMethod}, {@code path} and
     * additional headers. The values are converted into {@link String}s as explained in
     * <a href="HttpHeaders.html#object-values">Specifying a non-String header value</a>.
     */
    static RequestHeaders of(HttpMethod method, String path,
                             CharSequence name1, Object value1,
                             CharSequence name2, Object value2,
                             CharSequence name3, Object value3) {
        return builder(method, path).addObject(name1, value1)
                                    .addObject(name2, value2)
                                    .addObject(name3, value3)
                                    .build();
    }

    /**
     * Returns a new {@link RequestHeaders} with the specified {@link HttpMethod}, {@code path} and
     * additional headers.
     */
    static RequestHeaders of(HttpMethod method, String path,
                             CharSequence name1, String value1,
                             CharSequence name2, String value2,
                             CharSequence name3, String value3,
                             CharSequence name4, String value4) {
        return builder(method, path).add(name1, value1)
                                    .add(name2, value2)
                                    .add(name3, value3)
                                    .add(name4, value4)
                                    .build();
    }

    /**
     * Returns a new {@link RequestHeaders} with the specified {@link HttpMethod}, {@code path} and
     * additional headers. The values are converted into {@link String}s as explained in
     * <a href="HttpHeaders.html#object-values">Specifying a non-String header value</a>.
     */
    static RequestHeaders of(HttpMethod method, String path,
                             CharSequence name1, Object value1,
                             CharSequence name2, Object value2,
                             CharSequence name3, Object value3,
                             CharSequence name4, Object value4) {
        return builder(method, path).addObject(name1, value1)
                                    .addObject(name2, value2)
                                    .addObject(name3, value3)
                                    .addObject(name4, value4)
                                    .build();
    }

    /**
     * Returns a new {@link RequestHeaders} copied from the specified {@link HttpHeaders}.
     *
     * @throws IllegalArgumentException if the specified {@link HttpHeaders} does not have
     *                                  {@code ":method"} or {@code ":path"} header.
     */
    static RequestHeaders of(HttpHeaders headers) {
        if (headers instanceof RequestHeaders) {
            return (RequestHeaders) headers;
        }

        requireNonNull(headers, "headers");
        checkArgument(headers.contains(HttpHeaderNames.METHOD), ":method header does not exist.");
        checkArgument(headers.contains(HttpHeaderNames.PATH), ":path header does not exist.");

        if (headers instanceof HttpHeadersBase) {
            return new DefaultRequestHeaders((HttpHeadersBase) headers);
        }

        return new DefaultRequestHeaders(headers);
    }

    @Override
    RequestHeadersBuilder toBuilder();

    @Override
    default RequestHeaders withMutations(Consumer<HttpHeadersBuilder> mutator) {
        return (RequestHeaders) HttpHeaders.super.withMutations(mutator);
    }
}
