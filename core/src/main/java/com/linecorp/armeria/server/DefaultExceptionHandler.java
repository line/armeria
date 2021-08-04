/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.server;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.server.annotation.AnnotatedService;
import com.linecorp.armeria.server.annotation.ExceptionVerbosity;

/**
 * A default exception handler that is used when a user does not specify an {@link ExceptionHandler}.
 * It returns:
 * <ul>
 *     <li>an {@link HttpResponse} with {@code 400 Bad Request} status code when the cause is an
 *     {@link IllegalArgumentException} only for annotated service, or</li>
 *     <li>an {@link HttpResponse} with the status code that an {@link HttpStatusException} holds, or</li>
 *     <li>an {@link HttpResponse} with {@code 500 Internal Server Error}.</li>
 * </ul>
 */
enum DefaultExceptionHandler implements ExceptionHandler {

    INSTANCE;

    private static final Logger logger = LoggerFactory.getLogger(DefaultExceptionHandler.class);

    /**
     * Converts the specified {@link Throwable} to an {@link HttpResponse}.
     *
     * <p>Implementation note:
     * A failed {@link HttpResponse} should be returned in order to let a {@link RequestLog} complete deferred
     * values. The given cause could be raised before setting deferred values of a {@link RequestLog}.
     * See https://github.com/line/armeria/issues/3719.
     *
     * <p>For example:<pre>{@code
     * // Bad - Deferred values will not be completed.
     * HttpResponse.of(HttpStatus.BAD_REQUEST);
     * // Good - A LoggingService will not log any exception and complete deferred values.
     * HttpResponse.ofFailure(HttpStatusException.of(HttpStatus.BAD_REQUEST));
     * // Good - A LoggingService will log the IllegalStateException and complete deferred values.
     * HttpResponse.ofFailure(HttpStatusException.of(HttpStatus.BAD_REQUEST, new IllegalStateException(...)));
     * }</pre>
     */
    @Nullable
    @Override
    public HttpResponse convert(ServiceRequestContext context, Throwable cause) {
        // TODO(minwoox): Add more specific conditions such as returning 400 for IllegalArgumentException
        //                when we reach v2.0. Currently, an IllegalArgumentException is handled only for
        //                annotated services.
        final boolean isAnnotatedService = context.config().service().as(AnnotatedService.class) != null;
        if (isAnnotatedService) {
            if (cause instanceof IllegalArgumentException) {
                if (needsToWarn()) {
                    logger.warn("{} Failed processing a request:", context, cause);
                }
                return HttpResponse.ofFailure(HttpStatusException.of(HttpStatus.BAD_REQUEST, cause));
            }
        }

        if (cause instanceof HttpStatusException ||
            cause instanceof HttpResponseException) {
            // Use HttpStatusException or HttpResponseException itself because it already contains a status
            // or response.
            return HttpResponse.ofFailure(cause);
        }

        if (cause instanceof RequestCancellationException) {
            // A stream has been cancelled. No need to send a response with a status.
            return HttpResponse.ofFailure(cause);
        }

        if (cause instanceof RequestTimeoutException) {
            return HttpResponse.ofFailure(HttpStatusException.of(HttpStatus.SERVICE_UNAVAILABLE, cause));
        }

        if (isAnnotatedService && needsToWarn() && !Exceptions.isExpected(cause)) {
            logger.warn("{} Unhandled exception from a service:", context, cause);
        }

        return HttpResponse.ofFailure(HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR, cause));
    }

    private static boolean needsToWarn() {
        return Flags.annotatedServiceExceptionVerbosity() == ExceptionVerbosity.UNHANDLED &&
               logger.isWarnEnabled();
    }
}
