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

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A formatter that converts {@link RequestOnlyLog} or {@link RequestLog} into {@link String log message}.
 */
@UnstableApi
public interface LogFormatter {

    /**
     * Returns the default text {@link LogFormatter}.
     */
    static LogFormatter ofText() {
        return TextLogFormatter.DEFAULT_INSTANCE;
    }

    /**
     * Returns a newly created {@link TextLogFormatterBuilder}.
     */
    static TextLogFormatterBuilder builderForText() {
        return new TextLogFormatterBuilder();
    }

    /**
     * Returns the default JSON {@link LogFormatter} that converts a
     * {@link RequestOnlyLog} or {@link RequestLog} into a JSON format message.
     */
    static LogFormatter ofJson() {
        return JsonLogFormatter.DEFAULT_INSTANCE;
    }

    /**
     * Returns a newly created {@link LogFormatter} that converts a {@link RequestOnlyLog} or
     * {@link RequestLog} into JSON format log message by using the specified {@link ObjectMapper}.
     */
    static LogFormatter ofJson(ObjectMapper objectMapper) {
        return new JsonLogFormatterBuilder()
                .objectMapper(objectMapper)
                .build();
    }

    /**
     * Returns a newly created {@link JsonLogFormatterBuilder}.
     */
    static JsonLogFormatterBuilder builderForJson() {
        return new JsonLogFormatterBuilder();
    }

    /**
     * Returns the formatted request log message of the {@link RequestOnlyLog}.
     */
    String formatRequest(RequestOnlyLog log);

    /**
     * Returns the formatted response log message that is constructed by {@link RequestLog}.
     */
    String formatResponse(RequestLog log);
}
