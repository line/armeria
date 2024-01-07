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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * A builder implementation for {@link JsonHeadersSanitizer}.
 */
public final class JsonHeadersSanitizerBuilder extends AbstractHeadersSanitizerBuilder<JsonNode> {

    @Nullable
    private ObjectMapper objectMapper;

    /**
     * Sets the {@link Set} which includes headers to mask before logging.
     */
    @Override
    public JsonHeadersSanitizerBuilder maskingHeaders(CharSequence... headers) {
        return (JsonHeadersSanitizerBuilder) super.maskingHeaders(headers);
    }

    /**
     * Sets the {@link Set} which includes headers to mask before logging.
     */
    @Override
    public JsonHeadersSanitizerBuilder maskingHeaders(Iterable<? extends CharSequence> headers) {
        return (JsonHeadersSanitizerBuilder) super.maskingHeaders(headers);
    }

    /**
     * Sets the {@link Function} to use to mask headers before logging.
     */
    @Override
    public JsonHeadersSanitizerBuilder maskingFunction(Function<String, String> maskingFunction) {
        return (JsonHeadersSanitizerBuilder) super.maskingFunction(maskingFunction);
    }

    /**
     * Sets the {@link ObjectMapper} that will be used to convert headers into a {@link JsonNode}.
     */
    public JsonHeadersSanitizerBuilder objectMapper(ObjectMapper objectMapper) {
        this.objectMapper = requireNonNull(objectMapper, "objectMapper");
        return this;
    }

    /**
     * Returns a newly created JSON {@link HeadersSanitizer} based on the properties of this builder.
     */
    public JsonHeadersSanitizer build() {
        final ObjectMapper objectMapper = this.objectMapper != null ?
                                          this.objectMapper : JacksonUtil.newDefaultObjectMapper();

        final Set<CharSequence> maskingHeaders = maskingHeaders();
        return new JsonHeadersSanitizer(
                !maskingHeaders.isEmpty() ? maskingHeaders : defaultMaskingHeaders(), maskingFunction(),
                objectMapper);
    }
}
