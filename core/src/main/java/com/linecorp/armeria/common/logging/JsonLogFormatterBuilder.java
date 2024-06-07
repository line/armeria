/*
 * Copyright 2022 LINE Corporation
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

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * A builder implementation for {@link JsonLogFormatter}.
 */
@UnstableApi
public final class JsonLogFormatterBuilder
        extends AbstractLogFormatterBuilder<JsonLogFormatterBuilder, JsonNode> {

    @Nullable
    private ObjectMapper objectMapper;

    JsonLogFormatterBuilder() {}

    /**
     * Sets the {@link ObjectMapper} that will be used to convert an object into a JSON format message.
     */
    public JsonLogFormatterBuilder objectMapper(ObjectMapper objectMapper) {
        this.objectMapper = requireNonNull(objectMapper, "objectMapper");
        return this;
    }

    /**
     * Returns a newly-created JSON {@link LogFormatter} based on the properties of this builder.
     */
    public LogFormatter build() {
        final ObjectMapper objectMapper = this.objectMapper != null ?
                                          this.objectMapper : JacksonUtil.newDefaultObjectMapper();
        final HeadersSanitizer<JsonNode> defaultHeadersSanitizer =
                defaultHeadersSanitizer(objectMapper);
        final BiFunction<? super RequestContext, Object, JsonNode> defaultContentSanitizer =
                defaultSanitizer(objectMapper);
        return new JsonLogFormatter(
                firstNonNull(requestHeadersSanitizer(), HeadersSanitizer.ofJson()),
                firstNonNull(responseHeadersSanitizer(), HeadersSanitizer.ofJson()),
                firstNonNull(requestTrailersSanitizer(), defaultHeadersSanitizer),
                firstNonNull(responseTrailersSanitizer(), defaultHeadersSanitizer),
                firstNonNull(requestContentSanitizer(), defaultContentSanitizer),
                firstNonNull(responseContentSanitizer(), defaultContentSanitizer),
                objectMapper);
    }

    private static <T> BiFunction<? super RequestContext, T, JsonNode>
    defaultSanitizer(ObjectMapper objectMapper) {
        return (requestContext, obj) -> objectMapper.valueToTree(obj);
    }

    private static HeadersSanitizer<JsonNode>
    defaultHeadersSanitizer(ObjectMapper objectMapper) {
        return (requestContext, obj) -> objectMapper.valueToTree(obj);
    }
}
