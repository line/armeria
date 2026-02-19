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

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.CorsHeaderUtil;
import com.linecorp.armeria.server.cors.CorsService;

/**
 * A {@link DecoratingErrorHandlerFunction} for adding CORS headers to error responses.
 */
final class CorsErrorHandler implements DecoratingErrorHandlerFunction {

    private static void maybeSetCorsHeaders(ServiceRequestContext ctx) {
        final CorsService corsService = ctx.findService(CorsService.class);
        if (shouldSetCorsHeaders(corsService, ctx)) {
            assert corsService != null;
            ctx.mutateAdditionalResponseHeaders(builder -> {
                CorsHeaderUtil.setCorsResponseHeaders(ctx, ctx.request(), builder, corsService.config());
            });
        }
    }

    /**
     * Sets CORS headers for <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS#simple_requests">
     * simple CORS requests</a> or main requests.
     * Preflight requests is unsupported because we don't know if it is safe to perform the main request.
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

        return true;
    }

    @Nullable
    @Override
    public HttpResponse onServiceException(ServerErrorHandler delegate,
                                           ServiceRequestContext ctx, Throwable cause) {
        maybeSetCorsHeaders(ctx);
        return delegate.onServiceException(ctx, cause);
    }

    @Nullable
    @Override
    public HttpResponse onServiceException(ServiceErrorHandler delegate,
                                           ServiceRequestContext ctx, Throwable cause) {
        maybeSetCorsHeaders(ctx);
        return delegate.onServiceException(ctx, cause);
    }
}
