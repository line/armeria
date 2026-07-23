/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

/**
 * Builds a new {@link HeadersUpdatingClient} or its decorator function.
 */
public final class HeadersUpdatingClientBuilder {

    private final HeaderFunctionMap requestHeaders;
    private final HeaderFunctionMap responseHeaders;

    /**
     * Creates a new builder.
     */
    HeadersUpdatingClientBuilder() {
        requestHeaders = new HeaderFunctionMap(this);
        responseHeaders = new HeaderFunctionMap(this);
    }

    /**
     * Returns a {@link HeaderFunctionMap} to decorate request headers.
     */
    public HeaderFunctionMap requestHeaders() {
        return requestHeaders;
    }

    /**
     * Returns a {@link HeaderFunctionMap} to decorate response headers.
     */
    public HeaderFunctionMap responseHeaders() {
        return responseHeaders;
    }

    /**
     * Returns a newly created {@link HeadersUpdatingClient}  with the specified {@link HttpClient}.
     */
    public HeadersUpdatingClient build(HttpClient delegate) {
        return new HeadersUpdatingClient(delegate, requestHeaders.functionMap, responseHeaders.functionMap);
    }

    /**
     * Returns a newly created decorator that decorates an {@link HttpClient} with a new
     * {@link HeadersUpdatingClient} based on the properties of this builder.
     */
    public Function<? super HttpClient, HeadersUpdatingClient> newDecorator() {
        return this::build;
    }

    /**
     * Represents a mapping of header names to functions applied to those headers within the
     * {@link HeadersUpdatingClient}.
     */
    public static final class HeaderFunctionMap {
        private final HeadersUpdatingClientBuilder headersUpdatingClientBuilder;
        private final Map<CharSequence, Function<String, CompletableFuture<String>>> functionMap =
                new HashMap<>();

        HeaderFunctionMap(HeadersUpdatingClientBuilder headersUpdatingClientBuilder) {
            this.headersUpdatingClientBuilder = requireNonNull(headersUpdatingClientBuilder,
                                                               "headersUpdatingClientBuilder");
        }

        /**
         * Adds a header with the specified {@code name} and {@code value}.
         */
        public HeaderFunctionMap add(CharSequence name, String value) {
            requireNonNull(name, "name");
            requireNonNull(value, "value");
            final CharSequence headerName = HttpHeaderNames.of(name);
            functionMap.put(HttpHeaderNames.of(headerName),
                            header -> UnmodifiableFuture.completedFuture(value));
            return this;
        }

        /**
         * Adds a header with the specified {@code name} and {@code function}.
         */
        public HeaderFunctionMap add(CharSequence name, Function<@Nullable String, String> function) {
            requireNonNull(name, "name");
            requireNonNull(function, "function");
            final CharSequence headerName = HttpHeaderNames.of(name);
            final Function<String, CompletableFuture<String>> f =
                    functionMap.getOrDefault(headerName, UnmodifiableFuture::completedFuture);
            functionMap.put(headerName, header -> f.apply(header).thenApply(function));
            return this;
        }

        /**
         * Adds a header with the specified {@code name} and asynchronous {@code function}.
         */
        public HeaderFunctionMap addAsync(CharSequence name,
                                          Function<@Nullable String, CompletableFuture<String>> function) {
            requireNonNull(name, "name");
            requireNonNull(function, "function");
            final CharSequence headerName = HttpHeaderNames.of(name);
            final Function<String, CompletableFuture<String>> f =
                    functionMap.getOrDefault(headerName, CompletableFuture::completedFuture);
            functionMap.put(headerName, header -> f.apply(header).thenCompose(function));
            return this;
        }

        /**
         * Returns the parent {@link HeadersUpdatingClient}.
         */
        public HeadersUpdatingClientBuilder and() {
            return headersUpdatingClientBuilder;
        }
    }
}
