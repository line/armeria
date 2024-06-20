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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * A builder implementation for JSON {@link HeadersSanitizer}.
 */
public final class JsonHeadersSanitizerBuilder
        extends AbstractHeadersSanitizerBuilder<JsonHeadersSanitizerBuilder, JsonNode> {

    @Nullable
    private ObjectMapper objectMapper;

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
    public HeadersSanitizer<JsonNode> build() {
        final ObjectMapper objectMapper = this.objectMapper != null ?
                                          this.objectMapper : JacksonUtil.newDefaultObjectMapper();
        return new JsonHeadersSanitizer(sensitiveHeaders(), maskingFunction(), objectMapper);
    }
}
