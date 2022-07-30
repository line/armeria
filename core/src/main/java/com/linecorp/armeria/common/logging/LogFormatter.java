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
 * A formatter that convert {@link RequestLog} into {@link String log message} using {@link LogSanitizer}.
 */
@UnstableApi
public interface LogFormatter {

    /**
     * Returns {@link DefaultTextLogFormatter}.
     */
    static LogFormatter ofText() {
        return DefaultTextLogFormatter.INSTANCE;
    }

    /**
     * Returns {@link JsonLogFormatter} that convert {@link RequestLog} into json format log message.
     */
    static LogFormatter ofJson() {
        return JsonLogFormatter.DEFAULT_INSTANCE;
    }

    /**
     * Returns a newly created {@link JsonLogFormatter} that convert {@link RequestLog} into
     * json format log message by using specified {@link ObjectMapper}.
     */
    static LogFormatter ofJson(ObjectMapper objectMapper) {
        return new JsonLogFormatter(objectMapper);
    }

    /**
     * Returns the formatted request log message that is constructed by {@link RequestLog}
     * with using sanitizers.
     */
    String formatRequest(RequestLog log, LogSanitizer logSanitizer);

    /**
     * Returns the formatted response log message that is constructed by {@link RequestLog}
     * with using sanitizers.
     */
    String formatResponse(RequestLog log, LogSanitizer logSanitizer);
}
