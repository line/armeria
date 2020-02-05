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

package com.linecorp.armeria.internal.server.annotation;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.ExceptionVerbosity;

/**
 * A default exception handler is used when a user does not specify exception handlers
 * by {@link ExceptionHandler} annotation. It returns:
 * <ul>
 *     <li>an {@link HttpResponse} with {@code 400 Bad Request} status code when the cause is an
 *     {@link IllegalArgumentException}, or</li>
 *     <li>an {@link HttpResponse} with the status code that an {@link HttpStatusException} holds, or</li>
 *     <li>an {@link HttpResponse} that an {@link HttpResponseException} holds, or</li>
 *     <li>an {@link HttpResponse} with {@code 500 Internal Server Error}.</li>
 * </ul>
 */
final class DefaultExceptionHandler implements ExceptionHandlerFunction {
    private static final Logger logger = LoggerFactory.getLogger(DefaultExceptionHandler.class);

    @Override
    public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
        if (cause instanceof IllegalArgumentException) {
            log(log -> log.warn("{} Failed processing a request:", ctx, cause));
            return HttpResponse.of(HttpStatus.BAD_REQUEST);
        }

        if (cause instanceof HttpStatusException) {
            return HttpResponse.of(((HttpStatusException) cause).httpStatus());
        }

        if (cause instanceof HttpResponseException) {
            return ((HttpResponseException) cause).httpResponse();
        }

        log(log -> log.warn("{} Unhandled exception from an annotated service:", ctx, cause));

        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static void log(Consumer<Logger> logConsumer) {
        if (Flags.annotatedServiceExceptionVerbosity() == ExceptionVerbosity.UNHANDLED &&
            logger.isWarnEnabled()) {
            logConsumer.accept(logger);
        }
    }
}
