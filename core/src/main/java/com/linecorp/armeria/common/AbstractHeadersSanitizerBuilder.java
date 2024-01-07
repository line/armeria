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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * A skeletal builder implementation for {@link HeadersSanitizer}.
 */
abstract class AbstractHeadersSanitizerBuilder<T> {

    private final Set<CharSequence> maskingHeaders = new HashSet<>();

    private Function<String, String> mask = (header) -> "****";

    /**
     * Sets the {@link Set} which includes headers to mask before logging.
     */
    public AbstractHeadersSanitizerBuilder<T> maskingHeaders(CharSequence... headers) {
        requireNonNull(headers, "headers");
        Arrays.stream(headers).map(header -> header.toString().toLowerCase()).forEach(maskingHeaders::add);
        return this;
    }

    /**
     * Sets the {@link Set} which includes headers to mask before logging.
     */
    public AbstractHeadersSanitizerBuilder<T> maskingHeaders(Iterable<? extends CharSequence> headers) {
        requireNonNull(headers, "headers");
        headers.forEach(header -> maskingHeaders.add(header.toString().toLowerCase()));
        return this;
    }

    /**
     * Returns the {@link Set} which includes headers to mask before logging.
     */
    final Set<CharSequence> maskingHeaders() {
        return maskingHeaders;
    }

    /**
     * Sets the {@link Function} to use to maskFunction headers before logging.
     */
    public AbstractHeadersSanitizerBuilder<T> maskingFunction(Function<String, String> maskingFunction) {
        this.mask = requireNonNull(maskingFunction, "maskingFunction");
        return this;
    }

    /**
     * Returns the {@link Function} to use to mask headers before logging.
     */
    final Function<String, String> maskingFunction() {
        return mask;
    }

    protected final Set<CharSequence> defaultMaskingHeaders() {
        final HashSet<CharSequence> defaultMaskingHeaders = new HashSet<>();
        defaultMaskingHeaders.add(HttpHeaderNames.AUTHORIZATION.toLowerCase().toString());
        defaultMaskingHeaders.add(HttpHeaderNames.SET_COOKIE.toLowerCase().toString());
        return defaultMaskingHeaders;
    }
}
