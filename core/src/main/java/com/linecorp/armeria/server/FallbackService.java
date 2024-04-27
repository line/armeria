/*
 * Copyright 2019 LINE Corporation
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

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;

/**
 * Handles the {@link HttpRequest}s that are not matched by any user-specified {@link Route}s.
 */
final class FallbackService implements HttpService {

    static final FallbackService INSTANCE = new FallbackService();

    private FallbackService() {}

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final RoutingContext routingCtx = ctx.routingContext();

        if (routingCtx.status() == RoutingStatus.CORS_PREFLIGHT) {
            // '403 Forbidden' is better for a CORS preflight request than other statuses.
            return newFallbackResponse(ctx, HttpStatus.FORBIDDEN);
        }

        final HttpStatusException cause = routingCtx.deferredStatusException();
        if (cause == null || cause.httpStatus() == HttpStatus.NOT_FOUND) {
            return handleNotFound(ctx, routingCtx);
        }
        return newFallbackResponse(ctx, cause.httpStatus());
    }

    @Override
    public ExchangeType exchangeType(RoutingContext routingContext) {
        // Send an aggregated response as a workaround for the following issue:
        // 1) `FallbackService` returns a response and then the only headers are written to the channel.
        // 2) The client continues to send a payload that exceeds the maximum length.
        // 3) `Http{1,2}RequestDecoder` tries to fail the request with a 413 Request Entity Too Large response.
        // 4) As the headers have already been written at 1), `fail()` resets the connection.
        // 5) A 413 status or a 404 status is expected but the client ends up with a `ClosedSessionException`.
        return ExchangeType.REQUEST_STREAMING;
    }

    private static HttpResponse handleNotFound(ServiceRequestContext ctx, RoutingContext routingCtx) {
        // Handle 404 Not Found.
        final String oldPath = routingCtx.path();
        if (oldPath.charAt(oldPath.length() - 1) == '/') {
            // No need to send a redirect response because the request path already ends with '/'.
            return newFallbackResponse(ctx, HttpStatus.NOT_FOUND);
        }

        // Handle the case where '/path' (or '/path?query') doesn't exist
        // but '/path/' (or '/path/?query') exists.
        if (!ctx.config().virtualHost().findServiceConfig(routingCtx.withPath(oldPath + '/')).isPresent()) {
            // No need to send a redirect response because '/path/' (or '/path/?query') does not exist.
            return newFallbackResponse(ctx, HttpStatus.NOT_FOUND);
        }

        // Use relative path to handle the case where the server is behind a reverse proxy.
        // The reverse proxy might rewrite the path, so we should use the relative path.
        // For example, if the proxy rewrite the path /proxy/path -> /path, then we should send the location
        // with path/ so that the client can send the request to /proxy/path/ again.
        final int index = oldPath.lastIndexOf('/');
        assert index >= 0;
        String location = oldPath.substring(index + 1) + '/';
        if (routingCtx.query() != null) {
            location += '?' + routingCtx.query();
        }

        return HttpResponse.of(ResponseHeaders.builder(HttpStatus.TEMPORARY_REDIRECT)
                                              .add(HttpHeaderNames.LOCATION, location)
                                              .build());
    }

    private static HttpResponse newFallbackResponse(ServiceRequestContext ctx, HttpStatus status) {
        final ServiceErrorHandler errorHandler = ctx.config().errorHandler();
        final AggregatedHttpResponse rendered = errorHandler.renderStatus(ctx,
                                                                          ctx.request().headers(),
                                                                          status,
                                                                          null,
                                                                          null);
        if (rendered != null) {
            return rendered.toHttpResponse();
        }
        return HttpResponse.of(ResponseHeaders.builder(status)
                                              .endOfStream(true)
                                              .build());
    }
}
