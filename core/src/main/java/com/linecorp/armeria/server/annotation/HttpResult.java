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

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * An interface which helps a user specify an {@link HttpStatus} or {@link ResponseHeaders} for a response
 * produced by an annotated HTTP service method. The HTTP content can be specified as {@code content} as well,
 * and it would be converted into response body by a {@link ResponseConverterFunction}.
 *
 * @param <T> the type of a content which is to be converted into response body
 *
 * @deprecated Use {@link ResponseEntity} instead.
 */
@FunctionalInterface
@Deprecated
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
     * Creates a new {@link HttpResult} with the specified headers, content and trailers.
     *
     * @param headers the HTTP headers
     * @param content the content of the response
     * @param trailers the HTTP trailers
     */
    static <T> HttpResult<T> of(HttpHeaders headers, T content, HttpHeaders trailers) {
        return new DefaultHttpResult<>(headers, requireNonNull(content, "content"), trailers);
    }

    /**
     * Creates a new {@link HttpResult} with the specified {@link HttpStatus} and without content.
     *
     * @param status the HTTP status
     */
    static <T> HttpResult<T> of(HttpStatus status) {
        return new DefaultHttpResult<>(ResponseHeaders.of(status));
    }

    /**
     * Creates a new {@link HttpResult} with the specified {@link HttpStatus} and content.
     *
     * @param status the HTTP status
     * @param content the content of the response
     */
    static <T> HttpResult<T> of(HttpStatus status, T content) {
        return new DefaultHttpResult<>(ResponseHeaders.of(status), requireNonNull(content, "content"));
    }

    /**
     * Creates a new {@link HttpResult} with the specified {@link HttpStatus}, content and trailers.
     *
     * @param status the HTTP status
     * @param content the content of the response
     * @param trailers the HTTP trailers
     */
    static <T> HttpResult<T> of(HttpStatus status, T content, HttpHeaders trailers) {
        return new DefaultHttpResult<>(ResponseHeaders.of(status), requireNonNull(content, "content"),
                                       trailers);
    }

    /**
     * Creates a new {@link HttpResult} with the specified content and the {@link HttpStatus#OK} status.
     *
     * @param content the content of the response
     */
    static <T> HttpResult<T> of(T content) {
        return new DefaultHttpResult<>(ResponseHeaders.of(HttpStatus.OK),
                                       requireNonNull(content, "content"));
    }

    /**
     * Returns the response {@link HttpHeaders} which may not contain the {@code ":status"} header.
     * If the {@code ":status"} header does not exist, {@link HttpStatus#OK} or the status code specified in
     * the {@link StatusCode} annotation will be used.
     */
    HttpHeaders headers();

    /**
     * Returns an object which would be converted into response body.
     *
     * @return the response object, or {@code null} if the response object is not available.
     */
    @Nullable
    default T content() {
        return null;
    }

    /**
     * Returns the HTTP trailers of a response.
     */
    default HttpHeaders trailers() {
        return HttpHeaders.of();
    }
}
