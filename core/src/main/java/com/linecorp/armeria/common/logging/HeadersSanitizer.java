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

import java.util.function.BiFunction;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A sanitizer that sanitizes {@link HttpHeaders}.
 */
@FunctionalInterface
public interface HeadersSanitizer<T> extends BiFunction<RequestContext, HttpHeaders, T> {
    /**
     * Returns the default text {@link HeadersSanitizer} that masks
     * {@link HttpHeaderNames#AUTHORIZATION}, {@link HttpHeaderNames#COOKIE},
     * {@link HttpHeaderNames#SET_COOKIE}, and {@link HttpHeaderNames#PROXY_AUTHORIZATION} with {@code ****}.
     */
    static HeadersSanitizer<String> ofText() {
        return TextHeadersSanitizer.INSTANCE;
    }

    /**
     * Returns a newly created {@link TextHeadersSanitizerBuilder}.
     */
    static TextHeadersSanitizerBuilder builderForText() {
        return new TextHeadersSanitizerBuilder();
    }

    /**
     * Returns the default JSON {@link HeadersSanitizer} that masks
     * {@link HttpHeaderNames#AUTHORIZATION}, {@link HttpHeaderNames#COOKIE},
     * {@link HttpHeaderNames#SET_COOKIE}, and {@link HttpHeaderNames#PROXY_AUTHORIZATION} with {@code ****}.
     */
    static HeadersSanitizer<JsonNode> ofJson() {
        return JsonHeadersSanitizer.INSTANCE;
    }

    /**
     * Returns a newly created {@link JsonHeadersSanitizerBuilder}.
     */
    static JsonHeadersSanitizerBuilder builderForJson() {
        return new JsonHeadersSanitizerBuilder();
    }

    /**
     * Sanitizes the specified {@link HttpHeaders}.
     * If {@code null} is returned, the specified {@link HttpHeaders} will be excluded from the log.
     */
    @Nullable
    T sanitize(RequestContext requestContext, HttpHeaders headers);

    @Nullable
    @Override
    default T apply(RequestContext requestContext, HttpHeaders entries) {
        return sanitize(requestContext, entries);
    }
}
