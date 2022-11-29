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
            return HttpResponse.of(HttpStatus.FORBIDDEN);
        }

        final HttpStatusException cause = routingCtx.deferredStatusException();
        if (cause == null || cause.httpStatus() == HttpStatus.NOT_FOUND) {
            return handleNotFound(ctx, routingCtx);
        } else {
            return HttpResponse.of(cause.httpStatus());
        }
    }

    private static HttpResponse handleNotFound(ServiceRequestContext ctx, RoutingContext routingCtx) {
        // Handle 404 Not Found.
        final String oldPath = routingCtx.path();
        if (oldPath.charAt(oldPath.length() - 1) == '/') {
            // No need to send a redirect response because the request path already ends with '/'.
            return HttpResponse.of(HttpStatus.NOT_FOUND);
        }

        // Handle the case where '/path' (or '/path?query') doesn't exist
        // but '/path/' (or '/path/?query') exists.
        final String newPath = oldPath + '/';
        if (!ctx.config().virtualHost().findServiceConfig(routingCtx.overridePath(newPath)).isPresent()) {
            // No need to send a redirect response because '/path/' (or '/path/?query') does not exist.
            return HttpResponse.of(HttpStatus.NOT_FOUND);
        }

        // '/path/' (or '/path/?query') exists. Send a redirect response.
        final String location;
        if (routingCtx.query() == null) {
            // '/path/'
            location = newPath;
        } else {
            // '/path/?query'
            location = newPath + '?' + routingCtx.query();
        }

        return HttpResponse.of(ResponseHeaders.builder(HttpStatus.TEMPORARY_REDIRECT)
                                              .add(HttpHeaderNames.LOCATION, location)
                                              .build());
    }
}
