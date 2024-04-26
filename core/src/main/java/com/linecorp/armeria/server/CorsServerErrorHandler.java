package com.linecorp.armeria.server;

import com.linecorp.armeria.common.*;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.CorsHeaderUtil;
import com.linecorp.armeria.server.cors.CorsService;

public class CorsServerErrorHandler implements ServerErrorHandler {
    ServerErrorHandler serverErrorHandler;

    public CorsServerErrorHandler(ServerErrorHandler serverErrorHandler) {
        this.serverErrorHandler = serverErrorHandler;
    }

    @Override
    public @Nullable AggregatedHttpResponse renderStatus(@Nullable ServiceRequestContext ctx, ServiceConfig config, @Nullable RequestHeaders headers, HttpStatus status, @Nullable String description, @Nullable Throwable cause) {

        CorsService corsService = config.service().as(CorsService.class);

        if (corsService != null) {

            AggregatedHttpResponse res = serverErrorHandler.renderStatus(ctx, config, headers, status, description, cause);

            ResponseHeaders updatedResponseHeaders = addCorsHeaders(ctx, corsService, res.headers());

            AggregatedHttpResponse updatedRes = AggregatedHttpResponse.of(updatedResponseHeaders, res.content());

            return updatedRes;
        } else {
            return serverErrorHandler.renderStatus(ctx, config, headers, status, description, cause);
        }
    }

    private ResponseHeaders addCorsHeaders(ServiceRequestContext ctx, CorsService corsService, ResponseHeaders responseHeaders) {
        HttpRequest httpRequest = ctx.request();
        ResponseHeadersBuilder responseHeadersBuilder = responseHeaders.toBuilder();

        CorsHeaderUtil.setCorsResponseHeaders(ctx, httpRequest, responseHeadersBuilder, corsService);

        return responseHeadersBuilder.build();
    }

    @Override
    public @Nullable HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
        return this.serverErrorHandler.onServiceException(ctx, cause);
    }
}
