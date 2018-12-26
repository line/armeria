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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

import io.netty.util.AsciiString;

/**
 * A builder class which creates an {@link HttpResult}.
 */
public final class HttpResultBuilder<T> {

    private final HttpHeaders headers = HttpHeaders.of();
    @Nullable
    private T body;
    @Nullable
    private HttpHeaders trailingHeaders;

    /**
     * Sets an {@link HttpStatus} of a response.
     */
    public HttpResultBuilder<T> status(HttpStatus status) {
        headers.status(requireNonNull(status, "status"));
        return this;
    }

    /**
     * Sets an {@code status} of a response.
     */
    public HttpResultBuilder<T> status(int status) {
        headers.status(status);
        return this;
    }

    /**
     * Sets a {@code Content-Type} of a response.
     */
    public HttpResultBuilder<T> contentType(MediaType contentType) {
        headers.contentType(requireNonNull(contentType, "contentType"));
        return this;
    }

    /**
     * Adds a new header with the specified {@code name} and {@code value}.
     */
    public HttpResultBuilder<T> header(AsciiString name, String value) {
        headers.add(requireNonNull(name, "name"),
                    requireNonNull(value, "value"));
        return this;
    }

    /**
     * Adds a new header with the specified {@code name} and {@code value}.
     */
    public HttpResultBuilder<T> header(String name, String value) {
        headers.add(AsciiString.of(requireNonNull(name, "name")),
                    requireNonNull(value, "value"));
        return this;
    }

    /**
     * Adds all header names and values of {@code headers}.
     */
    public HttpResultBuilder<T> headers(HttpHeaders headers) {
        this.headers.add(requireNonNull(headers, "headers"));
        return this;
    }

    /**
     * Sets an object which would be converted into {@link HttpData} instances by a
     * {@link ResponseConverterFunction}.
     */
    public HttpResultBuilder<T> body(T body) {
        this.body = requireNonNull(body, "body");
        return this;
    }

    /**
     * Adds a new trailing header with the specified {@code name} and {@code value}.
     */
    public HttpResultBuilder<T> trailingHeader(AsciiString name, String value) {
        trailingHeaders().add(requireNonNull(name, "name"),
                              requireNonNull(value, "value"));
        return this;
    }

    /**
     * Adds a new trailing header with the specified {@code name} and {@code value}.
     */
    public HttpResultBuilder<T> trailingHeader(String name, String value) {
        trailingHeaders().add(AsciiString.of(requireNonNull(name, "name")),
                              requireNonNull(value, "value"));
        return this;
    }

    /**
     * Adds all trailing header names and values of {@code headers}.
     */
    public HttpResultBuilder<T> trailingHeaders(HttpHeaders trailingHeaders) {
        trailingHeaders().add(requireNonNull(trailingHeaders, "trailingHeaders"));
        return this;
    }

    private HttpHeaders trailingHeaders() {
        if (trailingHeaders == null) {
            trailingHeaders = HttpHeaders.of();
        }
        return trailingHeaders;
    }

    /**
     * Returns an {@link HttpResult} instance.
     */
    public HttpResult<T> build() {
        if (headers.status() == null) {
            headers.status(HttpStatus.OK);
        }
        return new DefaultHttpResult<>(headers, body, trailingHeaders);
    }
}
