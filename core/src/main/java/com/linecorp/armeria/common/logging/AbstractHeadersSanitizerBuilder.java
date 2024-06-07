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

package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AsciiString;

/**
 * A skeletal builder implementation for {@link HeadersSanitizer}.
 */
abstract class AbstractHeadersSanitizerBuilder<SELF extends AbstractHeadersSanitizerBuilder<SELF, T>, T> {

    // Referenced from:
    // - https://docs.rs/tower-http/latest/tower_http/sensitive_headers/index.html
    // - https://techdocs.akamai.com/edge-diagnostics/reference/sensitive-headers
    // - https://cloud.spring.io/spring-cloud-netflix/multi/multi__router_and_filter_zuul.html#_cookies_and_sensitive_headers
    private static final Set<AsciiString> DEFAULT_SENSITIVE_HEADERS =
            ImmutableSet.of(HttpHeaderNames.AUTHORIZATION, HttpHeaderNames.COOKIE,
                            HttpHeaderNames.SET_COOKIE, HttpHeaderNames.PROXY_AUTHORIZATION);

    @Nullable
    private Set<AsciiString> sensitiveHeaders;

    private HeaderMaskingFunction maskingFunction = HeaderMaskingFunction.of();

    @SuppressWarnings("unchecked")
    final SELF self() {
        return (SELF) this;
    }

    /**
     * Adds the headers to mask before logging.
     */
    public SELF sensitiveHeaders(CharSequence... headers) {
        requireNonNull(headers, "headers");
        return sensitiveHeaders(ImmutableSet.copyOf(headers));
    }

    /**
     * Adds the headers to mask before logging.
     */
    public SELF sensitiveHeaders(Iterable<? extends CharSequence> headers) {
        requireNonNull(headers, "headers");
        if (sensitiveHeaders == null) {
            sensitiveHeaders = new HashSet<>();
        }
        headers.forEach(header -> sensitiveHeaders.add(AsciiString.of(header).toLowerCase()));
        return self();
    }

    final Set<AsciiString> sensitiveHeaders() {
        if (sensitiveHeaders != null) {
            return ImmutableSet.copyOf(sensitiveHeaders);
        }
        return DEFAULT_SENSITIVE_HEADERS;
    }

    /**
     * Sets the {@link Function} to use to maskFunction headers before logging.
     * The default maskingFunction is {@link HeaderMaskingFunction#of()}
     *
     * <pre>{@code
     * builder.maskingFunction((name, value) -> {
     *   if (name.equals(HttpHeaderNames.AUTHORIZATION)) {
     *     return "****";
     *   } else if (name.equals(HttpHeaderNames.COOKIE)) {
     *     return name.substring(0, 4) + "****";
     *   } else {
     *     return value;
     *   }
     * }
     * }</pre>
     */
    public SELF maskingFunction(HeaderMaskingFunction maskingFunction) {
        this.maskingFunction = requireNonNull(maskingFunction, "maskingFunction");
        return self();
    }

    /**
     * Returns the {@link Function} to use to mask headers before logging.
     */
    final HeaderMaskingFunction maskingFunction() {
        return maskingFunction;
    }
}
