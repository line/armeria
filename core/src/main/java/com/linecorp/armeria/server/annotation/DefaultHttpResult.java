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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaders;

/**
 * A default {@link HttpResult} implementation.
 */
final class DefaultHttpResult<T> implements HttpResult<T> {

    private final HttpHeaders headers;
    @Nullable
    private final T body;
    private final HttpHeaders trailingHeaders;

    DefaultHttpResult(HttpHeaders headers) {
        this(headers, null);
    }

    DefaultHttpResult(HttpHeaders headers, @Nullable T body) {
        this(headers, body, HttpHeaders.EMPTY_HEADERS);
    }

    DefaultHttpResult(HttpHeaders headers,
                      @Nullable T body,
                      HttpHeaders trailingHeaders) {
        this.headers = requireNonNull(headers, "headers");
        this.body = body;
        this.trailingHeaders = requireNonNull(trailingHeaders, "trailingHeaders");
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @Override
    public Optional<T> body() {
        return Optional.ofNullable(body);
    }

    @Override
    public HttpHeaders trailingHeaders() {
        return trailingHeaders;
    }
}
