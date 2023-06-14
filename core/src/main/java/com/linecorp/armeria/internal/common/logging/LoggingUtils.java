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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientServiceOption;

/**
 * Utilities for logging decorators.
 */
public final class LoggingUtils {

    private static final Logger logger = LoggerFactory.getLogger(LoggingUtils.class);

    /**
     * Logs request and response using the specified {@link LogWriter}.
     */
    public static void log(RequestContext ctx, RequestLog requestLog, LogWriter logWriter) {
        if (requestLog.requestCause() != null || !isTransientService(ctx)) {
            try {
                logWriter.logRequest(requestLog);
            } catch (Throwable t) {
                logException(ctx, "request", t);
            }
        }
        if (requestLog.responseCause() != null ||
            requestLog.responseHeaders().status().isServerError() ||
            !isTransientService(ctx)) {
            try {
                logWriter.logResponse(requestLog);
            } catch (Throwable t) {
                logException(ctx, "response", t);
            }
        }
    }

    private static void logException(RequestContext ctx,
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

    private LoggingUtils() {}
}
