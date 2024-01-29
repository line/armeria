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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import com.google.common.collect.ImmutableSet;

import io.netty.util.AsciiString;

/**
 * A skeletal builder implementation for {@link HeadersSanitizer}.
 */
public abstract class AbstractHeadersSanitizerBuilder<T> {

    private static final Set<AsciiString> DEFAULT_MASKING_HEADERS =
            ImmutableSet.of(HttpHeaderNames.AUTHORIZATION, HttpHeaderNames.COOKIE,
                            HttpHeaderNames.SET_COOKIE, HttpHeaderNames.PROXY_AUTHORIZATION);

    private final Set<AsciiString> maskingHeaders = new HashSet<>();

    private Function<String, String> maskingFunction = header -> "****";

    /**
     * Sets the headers to mask before logging.
     */
    public AbstractHeadersSanitizerBuilder<T> maskingHeaders(CharSequence... headers) {
        requireNonNull(headers, "headers");
        return maskingHeaders(ImmutableSet.copyOf(headers));
    }

    /**
     * Sets the headers to mask before logging.
     */
    public AbstractHeadersSanitizerBuilder<T> maskingHeaders(Iterable<? extends CharSequence> headers) {
        requireNonNull(headers, "headers");
        headers.forEach(header -> maskingHeaders.add(AsciiString.of(header).toLowerCase()));
        return this;
    }

    final Set<AsciiString> maskingHeaders() {
        if (!maskingHeaders.isEmpty()) {
            return ImmutableSet.copyOf(maskingHeaders);
        }
        return DEFAULT_MASKING_HEADERS;
    }

    /**
     * Sets the {@link Function} to use to maskFunction headers before logging.
     */
    public AbstractHeadersSanitizerBuilder<T> maskingFunction(Function<String, String> maskingFunction) {
        this.maskingFunction = requireNonNull(maskingFunction, "maskingFunction");
        return this;
    }

    /**
     * Returns the {@link Function} to use to mask headers before logging.
     */
    final Function<String, String> maskingFunction() {
        return maskingFunction;
    }
}
