/*
 * Copyright 2024 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.server;

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.isCorsPreflightRequest;
import static com.linecorp.armeria.internal.server.CorsHeaderUtil.isForbiddenOrigin;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.CorsHeaderUtil;
import com.linecorp.armeria.server.cors.CorsService;

/**
 * wraps ServerErrorHandler for adding CORS headers to error responses.
 */
final class CorsServerErrorHandler implements ServerErrorHandler {
    ServerErrorHandler serverErrorHandler;

    /**
     * Constructs a new {@link CorsServerErrorHandler} instance with a specified {@link ServerErrorHandler}.
     * This handler is used to delegate server error handling for CORS-related errors.
     *
     * @param serverErrorHandler The {@link ServerErrorHandler} to be used for handling server errors.
     */
    CorsServerErrorHandler(ServerErrorHandler serverErrorHandler) {
        this.serverErrorHandler = serverErrorHandler;
    }

    @Nullable
    @Override
    public AggregatedHttpResponse renderStatus(@Nullable ServiceRequestContext ctx,
                                               ServiceConfig serviceConfig,
                                               @Nullable RequestHeaders headers,
                                               HttpStatus status, @Nullable String description,
                                               @Nullable Throwable cause) {
        return serverErrorHandler.renderStatus(ctx, serviceConfig, headers, status, description, cause);
    }

    @Nullable
    @Override
    public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
        final CorsService corsService = ctx.findService(CorsService.class);
        if (shouldSetCorsHeaders(corsService, ctx)) {
            assert corsService != null;
            ctx.mutateAdditionalResponseHeaders(builder -> {
                CorsHeaderUtil.setCorsResponseHeaders(ctx, ctx.request(), builder, corsService.config());
            });
        }
        return serverErrorHandler.onServiceException(ctx, cause);
    }

    /**
     * Sets CORS headers for <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS#simple_requests">
     * simple CORS requests</a>.
     * This method does not support preflight requests because they require a complete response that is
     * delegated to {@code serverErrorHandler}.
     */
    private static boolean shouldSetCorsHeaders(@Nullable CorsService corsService, ServiceRequestContext ctx) {
        if (corsService == null) {
            // No CorsService is configured.
            return false;
        }
        if (CorsHeaderUtil.isCorsHeadersSet(ctx)) {
            // CORS headers were set by CorsService.
            return false;
        }

        final RequestHeaders headers = ctx.request().headers();

        if (isCorsPreflightRequest(headers)) {
            return false;
        }
        //noinspection RedundantIfStatement
        if (isForbiddenOrigin(corsService.config(), ctx, headers)) {
            return false;
        }
        // A simple CORS request.
        return true;
    }

    @Nullable
    @Override
    public AggregatedHttpResponse onProtocolViolation(ServiceConfig config,
                                                      @Nullable RequestHeaders headers,
                                                      HttpStatus status, @Nullable String description,
                                                      @Nullable Throwable cause) {
        return serverErrorHandler.onProtocolViolation(config, headers, status, description, cause);
    }
}
