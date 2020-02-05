/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.concatPaths;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.isAbsoluteUri;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.common.PathAndQuery;

import io.micrometer.core.instrument.MeterRegistry;

final class DefaultWebClient extends UserClient<HttpRequest, HttpResponse> implements WebClient {

    private static final Logger logger = LoggerFactory.getLogger(DefaultWebClient.class);

    static final WebClient DEFAULT = new WebClientBuilder().build();

    DefaultWebClient(ClientBuilderParams params, HttpClient delegate, MeterRegistry meterRegistry) {
        super(params, delegate, meterRegistry);
    }

    @Override
    public HttpResponse execute(HttpRequest req) {
        URI uri;

        if (isAbsoluteUri(req.path())) {
            try {
                uri = URI.create(req.path());
            } catch (Exception ex) {
                logger.warn("Failed to create URI: {}", req.path(), ex);
                uri = null;
            }
        } else {
            uri = null;
        }

        if (uri != null) {
            final Endpoint endpoint = Endpoint.parse(uri.getAuthority());
            final String query = uri.getRawQuery();
            final String path = uri.getRawPath();
            final HttpRequest newReq = req.withHeaders(req.headers().toBuilder()
                    .path(query == null ? path : path + '?' + query));
            return execute(endpoint, newReq);
        }

        if (Clients.isUndefinedUri(uri())) {
            final IllegalArgumentException cause = new IllegalArgumentException("no authority: " + req.path());
            req.abort(cause);
            return HttpResponse.ofFailure(cause);
        }

        final String originalPath = req.path();
        final String newPath = concatPaths(uri().getRawPath(), originalPath);
        final HttpRequest newReq;
        // newPath and originalPath should be the same reference if uri().getRawPath() can be ignorable
        if (newPath != originalPath) {
            newReq = req.withHeaders(req.headers().toBuilder().path(newPath));
        } else {
            newReq = req;
        }
        return execute(endpointGroup(), newReq);
    }

    private HttpResponse execute(EndpointGroup endpointGroup, HttpRequest req) {
        final PathAndQuery pathAndQuery = PathAndQuery.parse(req.path());
        if (pathAndQuery == null) {
            final IllegalArgumentException cause = new IllegalArgumentException("invalid path: " + req.path());
            req.abort(cause);
            return HttpResponse.ofFailure(cause);
        }
        return execute(endpointGroup, req.method(),
                       pathAndQuery.path(), pathAndQuery.query(), null, req,
                       (ctx, cause) -> {
                           if (ctx != null && !ctx.log().isAvailable(RequestLogProperty.SESSION)) {
                               // An exception has been raised even before sending a request,
                               // so abort the request to release the elements.
                               if (cause == null) {
                                   req.abort();
                               } else {
                                   req.abort(cause);
                               }
                           }
                           return HttpResponse.ofFailure(cause);
                       });
    }

    @Override
    public HttpResponse execute(AggregatedHttpRequest aggregatedReq) {
        return execute(aggregatedReq.toHttpRequest());
    }
}
