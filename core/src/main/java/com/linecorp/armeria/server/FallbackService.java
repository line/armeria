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

import static java.util.Objects.requireNonNull;

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
        final HttpStatusException cause = requireNonNull(routingCtx.deferredStatusException());
        if (cause.httpStatus() != HttpStatus.NOT_FOUND) {
            throw cause;
        }

        final String path = req.path();
        if (path.charAt(path.length() - 1) == '/') {
            // The request path already ends with '/'. Send 404.
            throw cause;
        }

        // Handle the case where /path doesn't exist but /path/ exists.
        final String pathWithSlash = path + '/';
        if (!ctx.virtualHost().findServiceConfig(routingCtx.overridePath(pathWithSlash)).isPresent()) {
            // '/path/' does not exist. Send 404.
            throw cause;
        }

        // '/path/' exists. Send a redirect response.
        final String location;
        final String originalPath = req.path();
        if (path.length() == originalPath.length()) {
            location = pathWithSlash;
        } else {
            location = pathWithSlash + originalPath.substring(path.length());
        }

        return HttpResponse.of(ResponseHeaders.builder(HttpStatus.TEMPORARY_REDIRECT)
                                              .add(HttpHeaderNames.LOCATION, location)
                                              .build());
    }
}
