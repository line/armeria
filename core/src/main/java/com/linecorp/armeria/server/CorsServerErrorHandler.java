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

import static com.linecorp.armeria.internal.server.CorsHeaderUtil.addCorsHeaders;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.cors.CorsConfig;
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

    @Override
    public @Nullable AggregatedHttpResponse renderStatus(@Nullable ServiceRequestContext ctx,
                                                         ServiceConfig serviceConfig,
                                                         @Nullable RequestHeaders headers,
                                                         HttpStatus status, @Nullable String description,
                                                         @Nullable Throwable cause) {

        if (ctx == null) {
            return serverErrorHandler.renderStatus(null, serviceConfig, headers, status, description, cause);
        }

        final CorsService corsService = ctx.findService(CorsService.class);
        if (corsService == null) {
            return serverErrorHandler.renderStatus(ctx, serviceConfig, headers, status, description, cause);
        }

        final AggregatedHttpResponse res = serverErrorHandler.renderStatus(ctx, serviceConfig, headers, status,
                                                                           description, cause);

        if (res == null) {
            return serverErrorHandler.renderStatus(ctx, serviceConfig, headers, status, description, cause);
        }

        final CorsConfig corsConfig = corsService.config();
        final ResponseHeaders updatedResponseHeaders = addCorsHeaders(ctx, corsConfig,
                                                                      res.headers());

        return AggregatedHttpResponse.of(updatedResponseHeaders, res.content());
    }

    @Override
    public @Nullable HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
        if (cause instanceof HttpResponseException) {
            final HttpResponse oldRes = serverErrorHandler.onServiceException(ctx, cause);
            if (oldRes == null) {
                return null;
            }
            final CorsService corsService = ctx.findService(CorsService.class);
            if (corsService == null) {
                return oldRes;
            }
            return oldRes
                    .recover(HttpResponseException.class,
                             ex -> ex.httpResponse()
                                     .mapHeaders(oldHeaders -> addCorsHeaders(ctx,
                                                                              corsService.config(),
                                                                              oldHeaders)));
        } else {
            return serverErrorHandler.onServiceException(ctx, cause);
        }
    }
}
