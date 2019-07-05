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
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;

/**
 * Utilities for logging decorators.
 */
public final class LoggingDecorators {
    private static final String REQUEST_FORMAT = "Request: {}";
    private static final String RESPONSE_FORMAT = "Response: {}";
    private static final String RESPONSE_FORMAT2 = "Response: {}, cause: {}";

    private LoggingDecorators() {}

    /**
     * Logs a stringified request of {@link RequestLog}.
     */
    public static void logRequest(
            Logger logger, RequestLog log, LogLevel requestLogLevel,
            Function<? super RequestHeaders, ?> requestHeadersSanitizer,
            Function<Object, ?> requestContentSanitizer,
            Function<? super HttpHeaders, ?> requestTrailersSanitizer) {

        if (requestLogLevel.isEnabled(logger)) {
            requestLogLevel.log(logger, REQUEST_FORMAT,
                                log.toStringRequestOnly(requestHeadersSanitizer, requestContentSanitizer,
                                                        requestTrailersSanitizer));
        }
    }

    /**
     * Logs a stringified response of {@link RequestLog}.
     */
    public static void logResponse(
            Logger logger, RequestLog log, LogLevel requestLogLevel,
            Function<? super RequestHeaders, ?> requestHeadersSanitizer,
            Function<Object, ?> requestContentSanitizer,
            Function<? super HttpHeaders, ?> requestTrailersSanitizer,
            LogLevel successfulResponseLogLevel,
            LogLevel failedResponseLogLevel,
            Function<? super ResponseHeaders, ?> responseHeadersSanitizer,
            Function<Object, ?> responseContentSanitizer,
            Function<? super HttpHeaders, ?> responseTrailersSanitizer,
            Function<? super Throwable, ?> responseCauseSanitizer) {

        final Throwable responseCause = log.responseCause();
        final LogLevel level = responseCause == null ? successfulResponseLogLevel
                                                     : failedResponseLogLevel;
        if (level.isEnabled(logger)) {
            final String responseStr = log.toStringResponseOnly(responseHeadersSanitizer,
                                                                responseContentSanitizer,
                                                                responseTrailersSanitizer);
            if (responseCause == null) {
                level.log(logger, RESPONSE_FORMAT, responseStr);
            } else {
                if (!requestLogLevel.isEnabled(logger)) {
                    // Request wasn't logged but this is an unsuccessful response, log the request too to help
                    // debugging.
                    level.log(logger, REQUEST_FORMAT, log.toStringRequestOnly(requestHeadersSanitizer,
                                                                              requestContentSanitizer,
                                                                              requestTrailersSanitizer));
                }

                final Object sanitizedResponseCause = responseCauseSanitizer.apply(responseCause);
                if (sanitizedResponseCause != null) {
                    if (sanitizedResponseCause instanceof Throwable) {
                        level.log(logger, RESPONSE_FORMAT, responseStr, sanitizedResponseCause);
                    } else {
                        level.log(logger, RESPONSE_FORMAT2, responseStr, sanitizedResponseCause);
                    }
                } else {
                    level.log(logger, RESPONSE_FORMAT, responseStr);
                }
            }
        }
    }
}
