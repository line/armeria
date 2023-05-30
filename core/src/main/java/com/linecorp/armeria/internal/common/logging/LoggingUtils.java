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

import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.LogFormatter;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogLevelMapper;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.logging.ResponseLogLevelMapper;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientServiceOption;

/**
 * Utilities for logging decorators.
 */
public final class LoggingUtils {
    private static final String REQUEST_FORMAT = "{} Request: {}";
    private static final String RESPONSE_FORMAT = "{} Response: {}";

    /**
     * Logs request and response using the specified {@code requestLogger} and {@code responseLogger}.
     */
    public static void log(
            Logger logger, RequestContext ctx, RequestLog requestLog,
            Consumer<RequestOnlyLog> requestLogger, Consumer<RequestLog> responseLogger) {
        try {
            requestLogger.accept(requestLog);
        } catch (Throwable t) {
            logException(logger, ctx, "request", t);
        }
        try {
            responseLogger.accept(requestLog);
        } catch (Throwable t) {
            logException(logger, ctx, "response", t);
        }
    }

    /**
     * Logs a stringified request of {@link RequestOnlyLog}.
     */
    public static void logRequest(
            Logger logger, RequestOnlyLog log,
            RequestLogLevelMapper requestLogLevelMapper,
            LogFormatter logFormatter) {

        final LogLevel requestLogLevel = requestLogLevelMapper.apply(log);
        assert requestLogLevel != null;
        if (requestLogLevel.isEnabled(logger)) {
            final RequestContext ctx = log.context();
            if (log.requestCause() == null && isTransientService(ctx)) {
                return;
            }
            final String requestStr = logFormatter.formatRequest(log);

            try (SafeCloseable ignored = ctx.push()) {
                // We don't log requestCause when it's not null because responseCause is the same exception when
                // the requestCause is not null. That's way we don't have requestCauseSanitizer.
                requestLogLevel.log(logger, REQUEST_FORMAT, ctx, requestStr);
            }
        }
    }

    /**
     * Logs a stringified response of {@link RequestLog}.
     */
    public static void logResponse(
            Logger logger, RequestLog log,
            RequestLogLevelMapper requestLogLevelMapper,
            ResponseLogLevelMapper responseLogLevelMapper,
            Predicate<Throwable> responseCauseFilter,
            LogFormatter logFormatter) {

        final LogLevel responseLogLevel = responseLogLevelMapper.apply(log);
        assert responseLogLevel != null;
        final Throwable responseCause = log.responseCause();

        if (responseLogLevel.isEnabled(logger)) {
            final RequestContext ctx = log.context();
            if (responseCause == null &&
                !log.responseHeaders().status().isServerError() &&
                isTransientService(ctx)) {
                return;
            }

            final String responseStr = logFormatter.formatResponse(log);
            try (SafeCloseable ignored = ctx.push()) {
                if (responseCause == null) {
                    responseLogLevel.log(logger, RESPONSE_FORMAT, ctx, responseStr);
                    return;
                }

                final LogLevel requestLogLevel = requestLogLevelMapper.apply(log);
                assert requestLogLevel != null;
                if (!requestLogLevel.isEnabled(logger)) {
                    // Request wasn't logged, but this is an unsuccessful response,
                    // so we log the request too to help debugging.
                    responseLogLevel.log(logger, REQUEST_FORMAT, ctx, logFormatter.formatRequest(log));
                }

                if (responseCauseFilter.test(responseCause)) {
                    responseLogLevel.log(logger, RESPONSE_FORMAT, ctx, responseStr);
                } else {
                    responseLogLevel.log(logger, RESPONSE_FORMAT, ctx, responseStr, responseCause);
                }
            }
        }
    }

    private LoggingUtils() {}

    private static void logException(Logger logger, RequestContext ctx,
                                     String requestOrResponse, Throwable cause) {
        try (SafeCloseable ignored = ctx.push()) {
            logger.warn("{} Unexpected exception while logging {}: ", ctx, requestOrResponse, cause);
        }
    }

    private static boolean isTransientService(RequestContext ctx) {
        return ctx instanceof ServiceRequestContext &&
               !((ServiceRequestContext) ctx).config()
                                             .transientServiceOptions()
                                             .contains(TransientServiceOption.WITH_SERVICE_LOGGING);
    }
}
