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

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.RequestContextExtension;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;
import com.linecorp.armeria.server.annotation.AnnotatedService;

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

    /**
     * Converts the specified {@link Throwable} to an {@link HttpResponse}.
     */
    @Nonnull
    @Override
    public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
        // TODO(minwoox): Add more specific conditions such as returning 400 for IllegalArgumentException
        //                when we reach v2.0. Currently, an IllegalArgumentException is handled only for
        //                annotated services.
        final boolean isAnnotatedService = ctx.config().service().as(AnnotatedService.class) != null;
        if (isAnnotatedService) {
            if (cause instanceof IllegalArgumentException) {
                return internalRenderStatus(ctx, ctx.request().headers(),
                                            HttpStatus.BAD_REQUEST, cause);
            }
        }

        if (cause instanceof HttpStatusException ||
            cause instanceof HttpResponseException) {
            // Use HttpStatusException or HttpResponseException itself because it already contains a status
            // or response.
            return HttpResponse.ofFailure(cause);
        }

        if (cause instanceof ContentTooLargeException) {
            return internalRenderStatus(ctx, ctx.request().headers(),
                                        HttpStatus.REQUEST_ENTITY_TOO_LARGE, cause);
        }

        if (cause instanceof RequestCancellationException) {
            // A stream has been cancelled. No need to send a response with a status.
            return HttpResponse.ofFailure(cause);
        }

        if (cause instanceof RequestTimeoutException) {
            final HttpStatus status;
            final RequestContextExtension ctxExtension = ctx.as(RequestContextExtension.class);
            assert ctxExtension != null;
            final DecodedHttpRequest request = (DecodedHttpRequest) ctxExtension.originalRequest();
            if (request.isClosedSuccessfully()) {
                status = HttpStatus.SERVICE_UNAVAILABLE;
            } else {
                // The server didn't receive the request fully yet.
                status = HttpStatus.REQUEST_TIMEOUT;
            }
            return internalRenderStatus(ctx, ctx.request().headers(), status, cause);
        }

        return internalRenderStatus(ctx, ctx.request().headers(),
                                    HttpStatus.INTERNAL_SERVER_ERROR, cause);
    }

    private static HttpResponse internalRenderStatus(ServiceRequestContext ctx,
                                                     RequestHeaders headers,
                                                     HttpStatus status,
                                                     @Nullable Throwable cause) {
        final ServiceConfig serviceConfig = ctx.config();
        final AggregatedHttpResponse res =
                serviceConfig.server().config().errorHandler()
                             .renderStatus(ctx, serviceConfig, headers, status, null, cause);
        assert res != null;
        return res.toHttpResponse();
    }

    @Nonnull
    @Override
    public AggregatedHttpResponse renderStatus(@Nullable ServiceRequestContext ctx,
                                               ServiceConfig config,
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
