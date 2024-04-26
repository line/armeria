/*
 * Copyright 2024 LY Corporation
 *
 *         LY Corporation licenses this file to you under the Apache License,
 *         version 2.0 (the "License"); you may not use this file except in compliance
 *         with the License. You may obtain a copy of the License at:
 *
 *         https://www.apache.org/licenses/LICENSE-2.0
 *
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *         WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *         License for the specific language governing permissions and limitations
 *         under the License.
 */
package com.linecorp.armeria.server;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.CorsHeaderUtil;
import com.linecorp.armeria.server.cors.CorsService;

/**
 * wraps ServerErrorHandler for adding CORS headers to error responses.
 */
public class CorsServerErrorHandler implements ServerErrorHandler {
    ServerErrorHandler serverErrorHandler;

    /**
     * Constructs a new {@link CorsServerErrorHandler} instance with a specified {@link ServerErrorHandler}.
     * This handler is used to delegate server error handling for CORS-related errors.
     *
     * @param serverErrorHandler The {@link ServerErrorHandler} to be used for handling server errors.
     */
    public CorsServerErrorHandler(ServerErrorHandler serverErrorHandler) {
        this.serverErrorHandler = serverErrorHandler;
    }

    @Override
    public @Nullable AggregatedHttpResponse renderStatus(@Nullable ServiceRequestContext ctx,
                                                         ServiceConfig config, @Nullable RequestHeaders headers,
                                                         HttpStatus status, @Nullable String description,
                                                         @Nullable Throwable cause) {

        final CorsService corsService = config.service().as(CorsService.class);

        if (corsService == null || ctx == null) {
            return serverErrorHandler.renderStatus(ctx, config, headers, status, description, cause);
        }

        final AggregatedHttpResponse res = serverErrorHandler.renderStatus(ctx, config, headers, status,
                                                                           description, cause);

        if (res == null) {
            return serverErrorHandler.renderStatus(ctx, config, headers, status, description, cause);
        }

        final ResponseHeaders updatedResponseHeaders = addCorsHeaders(ctx, corsService, res.headers());

        return AggregatedHttpResponse.of(updatedResponseHeaders, res.content());
    }

    private static ResponseHeaders addCorsHeaders(ServiceRequestContext ctx, CorsService corsService,
                                                  ResponseHeaders responseHeaders) {
        final HttpRequest httpRequest = ctx.request();
        final ResponseHeadersBuilder responseHeadersBuilder = responseHeaders.toBuilder();

        CorsHeaderUtil.setCorsResponseHeaders(ctx, httpRequest, responseHeadersBuilder, corsService);

        return responseHeadersBuilder.build();
    }

    @Override
    public @Nullable HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
        return serverErrorHandler.onServiceException(ctx, cause);
    }
}
