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

package com.linecorp.armeria.internal.common.logging;

import java.util.function.Function;

import org.slf4j.Logger;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;

/**
 * Utilities for logging decorators.
 */
public final class LoggingDecorators {
    private static final String REQUEST_FORMAT = "{} Request: {}";
    private static final String RESPONSE_FORMAT = "{} Response: {}";
    private static final String RESPONSE_FORMAT2 = "{} Response: {}, cause: {}";

    private LoggingDecorators() {}

    /**
     * Logs a stringified request of {@link RequestLog}.
     */
    public static void logRequest(
            Logger logger, RequestOnlyLog log,
            Function<? super RequestOnlyLog, LogLevel> requestLogLevelMapper,
            Function<? super RequestHeaders, ?> requestHeadersSanitizer,
            Function<Object, ?> requestContentSanitizer,
            Function<? super HttpHeaders, ?> requestTrailersSanitizer) {

        final LogLevel requestLogLevel = requestLogLevelMapper.apply(log);
        if (requestLogLevel.isEnabled(logger)) {
            requestLogLevel.log(logger, REQUEST_FORMAT, log.context(),
                                log.toStringRequestOnly(requestHeadersSanitizer, requestContentSanitizer,
                                                        requestTrailersSanitizer));
        }
    }

    /**
     * Logs a stringified response of {@link RequestLog}.
     */
    public static void logResponse(
            Logger logger, RequestLog log,
            Function<? super RequestLog, LogLevel> requestLogLevelMapper,
            Function<? super RequestLog, LogLevel> responseLogLevelMapper,
            Function<? super RequestHeaders, ?> requestHeadersSanitizer,
            Function<Object, ?> requestContentSanitizer,
            Function<? super HttpHeaders, ?> requestTrailersSanitizer,
            Function<? super ResponseHeaders, ?> responseHeadersSanitizer,
            Function<Object, ?> responseContentSanitizer,
            Function<? super HttpHeaders, ?> responseTrailersSanitizer,
            Function<? super Throwable, ?> responseCauseSanitizer) {
        final LogLevel responseLogLevel = responseLogLevelMapper.apply(log);
        final Throwable responseCause = log.responseCause();

        if (responseLogLevel.isEnabled(logger)) {
            final RequestContext ctx = log.context();
            final String responseStr = log.toStringResponseOnly(responseHeadersSanitizer,
                                                                responseContentSanitizer,
                                                                responseTrailersSanitizer);
            if (responseCause == null) {
                responseLogLevel.log(logger, RESPONSE_FORMAT, ctx, responseStr);
            } else {
                final LogLevel requestLogLevel = requestLogLevelMapper.apply(log);
                if (!requestLogLevel.isEnabled(logger)) {
                    // Request wasn't logged but this is an unsuccessful response, log the request too to help
                    // debugging.
                    responseLogLevel.log(logger, REQUEST_FORMAT, ctx,
                                         log.toStringRequestOnly(requestHeadersSanitizer,
                                                                 requestContentSanitizer,
                                                                 requestTrailersSanitizer));
                }

                final Object sanitizedResponseCause = responseCauseSanitizer.apply(responseCause);
                if (sanitizedResponseCause != null) {
                    if (sanitizedResponseCause instanceof Throwable) {
                        responseLogLevel.log(logger, RESPONSE_FORMAT, ctx,
                                             responseStr, sanitizedResponseCause);
                    } else {
                        responseLogLevel.log(logger, RESPONSE_FORMAT2, ctx,
                                             responseStr, sanitizedResponseCause);
                    }
                } else {
                    responseLogLevel.log(logger, RESPONSE_FORMAT, ctx, responseStr);
                }
            }
        }
    }
}
