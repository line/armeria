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
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A default {@link HttpResult} implementation.
 */
final class DefaultHttpResult<T> implements HttpResult<T> {

    private final HttpHeaders headers;
    @Nullable
    private final T content;
    private final HttpHeaders trailers;

    DefaultHttpResult(HttpHeaders headers) {
        this(headers, null, HttpHeaders.of());
    }

    DefaultHttpResult(HttpHeaders headers, T content) {
        this(headers, requireNonNull(content, "content"), HttpHeaders.of());
    }

    DefaultHttpResult(HttpHeaders headers, @Nullable T content, HttpHeaders trailers) {
        this.headers = requireNonNull(headers, "headers");
        this.content = content;
        this.trailers = requireNonNull(trailers, "trailers");
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @Override
    public T content() {
        return content;
    }

    @Override
    public HttpHeaders trailers() {
        return trailers;
    }
}
