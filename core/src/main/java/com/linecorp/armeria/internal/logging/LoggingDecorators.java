/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal.logging;

import java.util.function.Function;

import org.slf4j.Logger;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;

/**
 * Utilities for logging decorators.
 */
public final class LoggingDecorators {
    private static final String REQUEST_FORMAT = "Request: {}";
    private static final String RESPONSE_FORMAT = "Response: {}";

    private LoggingDecorators() {}

    /**
     * Logs a stringified request of {@link RequestLog}.
     */
    public static void logRequest(
            Logger logger, RequestLog log, LogLevel requestLogLevel,
            Function<? super HttpHeaders, ? extends HttpHeaders> requestHeadersSanitizer,
            Function<Object, ?> requestContentSanitizer) {

        if (requestLogLevel.isEnabled(logger)) {
            requestLogLevel.log(logger, REQUEST_FORMAT,
                                log.toStringRequestOnly(requestHeadersSanitizer, requestContentSanitizer));
        }
    }

    /**
     * Logs a stringified response of {@link RequestLog}.
     */
    public static void logResponse(
            Logger logger, RequestLog log, LogLevel requestLogLevel,
            Function<? super HttpHeaders, ? extends HttpHeaders> requestHeadersSanitizer,
            Function<Object, ?> requestContentSanitizer,
            LogLevel successfulResponseLogLevel,
            LogLevel failedResponseLogLevel,
            Function<? super HttpHeaders, ? extends HttpHeaders> responseHeadersSanitizer,
            Function<Object, ?> responseContentSanitizer,
            Function<? super Throwable, ? extends Throwable> responseCauseSanitizer) {

        final Throwable responseCause = log.responseCause();
        final LogLevel level = responseCause == null ? successfulResponseLogLevel
                                                     : failedResponseLogLevel;
        if (level.isEnabled(logger)) {
            final String responseStr = log.toStringResponseOnly(responseHeadersSanitizer,
                                                                responseContentSanitizer);
            if (responseCause == null) {
                level.log(logger, RESPONSE_FORMAT, responseStr);
            } else {
                if (!requestLogLevel.isEnabled(logger)) {
                    // Request wasn't logged but this is an unsuccessful response, log the request too to help
                    // debugging.
                    level.log(logger, REQUEST_FORMAT, log.toStringRequestOnly(requestHeadersSanitizer,
                                                                              requestContentSanitizer));
                }

                final Throwable sanitizedResponseCause = responseCauseSanitizer.apply(responseCause);
                if (sanitizedResponseCause != null) {
                    level.log(logger, RESPONSE_FORMAT, responseStr, sanitizedResponseCause);
                } else {
                    level.log(logger, RESPONSE_FORMAT, responseStr);
                }
            }
        }
    }
}
