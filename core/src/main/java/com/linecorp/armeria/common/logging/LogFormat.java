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

import java.util.function.BiFunction;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A formatter that convert {@link RequestLog} into {@link String log message} using {@link LogSanitizers}.
 */
public interface LogFormat {

    /**
     * Returns {@link DefaultTextLogFormat}.
     */
    static LogFormat ofText() {
        return new DefaultTextLogFormat();
    }

    /**
     * Returns {@link JsonLogFormat} that convert {@link RequestLog} into json format log message.
     */
    static LogFormat ofJson() {
        return new JsonLogFormat();
    }

    /**
     * Returns the formatted request log message that is constructed by {@link RequestLog}
     * with using sanitizers.
     */
    String formatRequest(RequestLog log, LogSanitizers<RequestHeaders> sanitizers);

    /**
     * Returns the formatted response log message that is constructed by {@link RequestLog}
     * with using sanitizers.
     */
    String formatResponse(RequestLog log, LogSanitizers<ResponseHeaders> sanitizers);

    /**
     * Applies the {@link BiFunction} to {@link RequestLog} to sanitize the header and returns it.
     */
    default <T> String sanitize(
            RequestLog log,
            BiFunction<? super RequestContext, ? super T, ? extends @Nullable Object> headersSanitizer,
            T requestHeaders) {
        final Object sanitized = headersSanitizer.apply(log.context(), requestHeaders);
        return sanitized != null ? sanitized.toString() : "<sanitized>";
    }
}
