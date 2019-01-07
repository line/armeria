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
package com.linecorp.armeria.server.annotation;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;

/**
 * An interface which helps a user specify an {@link HttpStatus} and {@link HttpHeaders} for a response
 * produced by an annotated HTTP service method. The HTTP content can be specified as {@code content} as well,
 * and it would be converted into response body by a {@link ResponseConverterFunction}.
 *
 * @param <T> the type of a content which is to be converted into response body
 */
@FunctionalInterface
public interface HttpResult<T> {

    /**
     * Creates a new {@link HttpResult} with the specified headers and without content.
     *
     * @param headers the HTTP headers
     */
    static <T> HttpResult<T> of(HttpHeaders headers) {
        return new DefaultHttpResult<>(headers);
    }

    /**
     * Creates a new {@link HttpResult} with the specified headers and content.
     *
     * @param headers the HTTP headers
     * @param content the content of the response
     */
    static <T> HttpResult<T> of(HttpHeaders headers, T content) {
        return new DefaultHttpResult<>(headers, requireNonNull(content, "content"));
    }

    /**
     * Creates a new {@link HttpResult} with the specified headers, content and trailing headers.
     *
     * @param headers the HTTP headers
     * @param content the content of the response
     * @param trailingHeaders the trailing HTTP headers
     */
    static <T> HttpResult<T> of(HttpHeaders headers, T content, HttpHeaders trailingHeaders) {
        return new DefaultHttpResult<>(headers,
                                       requireNonNull(content, "content"),
                                       trailingHeaders);
    }

    /**
     * Creates a new {@link HttpResult} with the specified {@link HttpStatus} and without content.
     *
     * @param status the HTTP status
     */
    static <T> HttpResult<T> of(HttpStatus status) {
        return new DefaultHttpResult<>(HttpHeaders.of(status));
    }

    /**
     * Creates a new {@link HttpResult} with the specified {@link HttpStatus} and content.
     *
     * @param status the HTTP status
     * @param content the content of the response
     */
    static <T> HttpResult<T> of(HttpStatus status, T content) {
        return new DefaultHttpResult<>(HttpHeaders.of(status), requireNonNull(content, "content"));
    }

    /**
     * Creates a new {@link HttpResult} with the specified {@link HttpStatus}, content and trailing headers.
     *
     * @param status the HTTP status
     * @param content the content of the response
     * @param trailingHeaders the trailing HTTP headers
     */
    static <T> HttpResult<T> of(HttpStatus status, T content, HttpHeaders trailingHeaders) {
        return new DefaultHttpResult<>(HttpHeaders.of(status),
                                       requireNonNull(content, "content"),
                                       trailingHeaders);
    }

    /**
     * Creates a new {@link HttpResult} with the specified content and the {@link HttpStatus#OK} status.
     *
     * @param content the content of the response
     */
    static <T> HttpResult<T> of(T content) {
        return new DefaultHttpResult<>(HttpHeaders.of(HttpStatus.OK),
                                       requireNonNull(content, "content"));
    }

    /**
     * Returns {@link HttpHeaders} of a response.
     */
    HttpHeaders headers();

    /**
     * Returns an object which would be converted into response body.
     */
    default Optional<T> content() {
        return Optional.empty();
    }

    /**
     * Returns trailing {@link HttpHeaders} of a response.
     */
    default HttpHeaders trailingHeaders() {
        return HttpHeaders.EMPTY_HEADERS;
    }
}
