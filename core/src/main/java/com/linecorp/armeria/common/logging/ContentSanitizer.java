/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A content sanitizer implementation which sanitizes an object before serializing it into a
 * log friendly format. The following illustrates a simple use-case.
 * <pre>{@code
 * LogFormatter
 *         .builderForText()
 *         .contentSanitizer(ContentSanitizer.builder()
 *                                           .fieldMaskerSelector(...)
 *                                           .buildForText())
 *         .build();
 * }</pre>
 *
 * @see TextLogFormatterBuilder#contentSanitizer(BiFunction)
 * @see JsonLogFormatterBuilder#contentSanitizer(BiFunction)
 */
@UnstableApi
@FunctionalInterface
public interface ContentSanitizer<T> extends BiFunction<RequestContext, Object, T> {

    /**
     * Returns a {@link ContentSanitizerBuilder} instance.
     */
    static ContentSanitizerBuilder builder() {
        return new ContentSanitizerBuilder();
    }

    /**
     * Returns a {@link ContentSanitizer} instance which doesn't mask fields.
     */
    static ContentSanitizer<String> forText() {
        return new ContentSanitizerBuilder().buildForText();
    }

    /**
     * Returns a {@link ContentSanitizer} instance which doesn't mask fields.
     */
    static ContentSanitizer<JsonNode> forJson() {
        return new ContentSanitizerBuilder().buildForJson();
    }
}
