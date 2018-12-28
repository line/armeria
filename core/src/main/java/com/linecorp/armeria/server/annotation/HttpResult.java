/*
 * Copyright 2018 LINE Corporation
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

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;

/**
 * An interface which helps a user specify an {@link HttpStatus} and {@link HttpHeaders} for a response
 * produced by an annotated HTTP service method. The HTTP content can be specified as {@code body} as well,
 * and it would be converted into {@link HttpData} instances by a {@link ResponseConverterFunction}.
 *
 * @param <T> the type of a body which is to be contained in an {@link HttpResponse}
 */
@FunctionalInterface
public interface HttpResult<T> {

    static <T> HttpResult<T> of(HttpHeaders headers) {
        return new DefaultHttpResult<>(headers);
    }

    static <T> HttpResult<T> of(HttpHeaders headers, T body) {
        return new DefaultHttpResult<>(headers,
                                       requireNonNull(body, "body"));
    }

    static <T> HttpResult<T> of(HttpHeaders headers, T body, HttpHeaders trailingHeaders) {
        return new DefaultHttpResult<>(headers,
                                       requireNonNull(body, "body"),
                                       trailingHeaders);
    }

    static <T> HttpResult<T> of(HttpStatus status) {
        return new DefaultHttpResult<>(HttpHeaders.of(status));
    }

    static <T> HttpResult<T> of(HttpStatus status, T body) {
        return new DefaultHttpResult<>(HttpHeaders.of(status),
                                       requireNonNull(body, "body"));
    }

    static <T> HttpResult<T> of(HttpStatus status, T body, HttpHeaders trailingHeaders) {
        return new DefaultHttpResult<>(HttpHeaders.of(status),
                                       requireNonNull(body, "body"),
                                       trailingHeaders);
    }

    static <T> HttpResult<T> of(T body) {
        return new DefaultHttpResult<>(HttpHeaders.of(HttpStatus.OK),
                                       requireNonNull(body, "body"));
    }

    static <T> HttpResult<T> of(T body, HttpHeaders trailingHeaders) {
        return new DefaultHttpResult<>(HttpHeaders.of(HttpStatus.OK),
                                       requireNonNull(body, "body"),
                                       trailingHeaders);
    }

    /**
     * Returns {@link HttpHeaders} of a response.
     */
    HttpHeaders headers();

    /**
     * Returns an object which would be converted into {@link HttpData} instances.
     */
    default Optional<T> body() {
        return Optional.empty();
    }

    /**
     * Returns trailing {@link HttpHeaders} of a response.
     */
    default HttpHeaders trailingHeaders() {
        return HttpHeaders.EMPTY_HEADERS;
    }
}
