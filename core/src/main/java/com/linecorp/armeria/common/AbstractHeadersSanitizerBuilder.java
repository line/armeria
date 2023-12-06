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

import java.util.Set;
import java.util.function.Function;

import com.google.common.collect.ImmutableSet;

/**
 * A skeletal builder implementation for {@link HeadersSanitizer}.
 */
abstract class AbstractHeadersSanitizerBuilder<T> {

    private Set<String> maskHeaders = ImmutableSet.of();

    private Function<String, String> mask = (header) -> "****";

    /**
     * Sets the {@link Set} which includes headers to mask before logging.
     */
    public AbstractHeadersSanitizerBuilder<T> maskHeaders(String... headers) {
        maskHeaders = ImmutableSet.copyOf(requireNonNull(headers, "headers"));
        return this;
    }

    /**
     * Sets the {@link Set} which includes headers to mask before logging.
     */
    public AbstractHeadersSanitizerBuilder<T> maskHeaders(Iterable<String> headers) {
        maskHeaders = ImmutableSet.copyOf(requireNonNull(headers, "headers"));
        return this;
    }

    /**
     * Returns the {@link Set} which includes headers to mask before logging.
     */
    final Set<String> maskHeaders() {
        return maskHeaders;
    }

    /**
     * Sets the {@link Function} to use to mask headers before logging.
     */
    public AbstractHeadersSanitizerBuilder<T> mask(Function<String, String> mask) {
        this.mask = requireNonNull(mask, "mask");
        return this;
    }

    /**
     * Returns the {@link Function} to use to mask headers before logging.
     */
    final Function<String, String> mask() {
        return mask;
    }
}
