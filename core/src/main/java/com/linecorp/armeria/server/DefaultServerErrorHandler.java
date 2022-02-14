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

import static com.google.common.base.MoreObjects.firstNonNull;

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;
import com.linecorp.armeria.internal.server.annotation.AnnotatedService;
import com.linecorp.armeria.server.annotation.ExceptionVerbosity;

/**
 * The default {@link ServerErrorHandler} that is used when a user didn't specify one.
 * It returns:
 * <ul>
 *     <li>an {@link HttpResponse} with {@code 400 Bad Request} status code when the cause is an
 *     {@link IllegalArgumentException} only for annotated service, or</li>
 *     <li>an {@link HttpResponse} with the status code that an {@link HttpStatusException} holds, or</li>
 *     <li>an {@link HttpResponse} with {@code 500 Internal Server Error}.</li>
 * </ul>
 */
enum DefaultServerErrorHandler implements ServerErrorHandler {

    INSTANCE;

    private static final Logger logger = LoggerFactory.getLogger(DefaultServerErrorHandler.class);

    /**
     * Converts the specified {@link Throwable} to an {@link HttpResponse}.
     */
    @Nonnull
    @Override
    public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
        // TODO(minwoox): Add more specific conditions such as returning 400 for IllegalArgumentException
        //                when we reach v2.0. Currently, an IllegalArgumentException is handled only for
        //                annotated services.
        final ServiceConfig serviceConfig = ctx.config();
        final boolean isAnnotatedService = serviceConfig.service().as(AnnotatedService.class) != null;
        if (isAnnotatedService) {
            if (cause instanceof IllegalArgumentException) {
                if (needsToWarn()) {
                    logger.warn("{} Failed processing a request:", ctx, cause);
                }

                return internalRenderStatus(serviceConfig, ctx.request().headers(),
                                            HttpStatus.BAD_REQUEST, cause);
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
            return internalRenderStatus(serviceConfig, ctx.request().headers(),
                                        HttpStatus.SERVICE_UNAVAILABLE, cause);
        }

        if (isAnnotatedService && needsToWarn() && !Exceptions.isExpected(cause)) {
            logger.warn("{} Unhandled exception from a service:", ctx, cause);
        }

        return internalRenderStatus(serviceConfig, ctx.request().headers(),
                                    HttpStatus.INTERNAL_SERVER_ERROR, cause);
    }

    @SuppressWarnings("deprecation")
    private static boolean needsToWarn() {
        return Flags.annotatedServiceExceptionVerbosity() == ExceptionVerbosity.UNHANDLED &&
               logger.isWarnEnabled();
    }

    private static HttpResponse internalRenderStatus(ServiceConfig serviceConfig,
                                                     RequestHeaders headers,
                                                     HttpStatus status,
                                                     @Nullable Throwable cause) {
        final AggregatedHttpResponse res =
                serviceConfig.server().config().errorHandler()
                             .renderStatus(serviceConfig, headers, status, null, cause);
        assert res != null;
        return res.toHttpResponse();
    }

    @Nonnull
    @Override
    public AggregatedHttpResponse renderStatus(ServiceConfig config,
                                               @Nullable RequestHeaders headers,
                                               HttpStatus status,
                                               @Nullable String description,
                                               @Nullable Throwable cause) {
        if (status.isContentAlwaysEmpty()) {
            return AggregatedHttpResponse.of(ResponseHeaders.of(status));
        }

        final HttpData content;
        try (TemporaryThreadLocals ttl = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = ttl.stringBuilder();
            buf.append("Status: ").append(status.codeAsText()).append('\n');
            buf.append("Description: ").append(firstNonNull(description, status.reasonPhrase())).append('\n');
            if (cause != null && config.verboseResponses() && !status.isSuccess()) {
                buf.append("Stack trace:\n");
                buf.append(Exceptions.traceText(cause));
            }
            content = HttpData.ofUtf8(buf);
        }

        return AggregatedHttpResponse.of(status, MediaType.PLAIN_TEXT_UTF_8, content);
    }
}
