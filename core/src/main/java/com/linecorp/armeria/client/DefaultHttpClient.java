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

import static com.linecorp.armeria.client.AsyncHttpClientBuilder.isUndefinedUri;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.concatPaths;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.isAbsoluteUri;

import java.net.URI;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.internal.PathAndQuery;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;

final class DefaultHttpClient extends UserClient<HttpRequest, HttpResponse> implements AsyncHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(DefaultHttpClient.class);

    DefaultHttpClient(ClientBuilderParams params, HttpClient delegate,
                      MeterRegistry meterRegistry, SessionProtocol sessionProtocol, Endpoint endpoint) {
        super(params, delegate, meterRegistry, sessionProtocol, endpoint);
    }

    @Override
    public HttpResponse execute(HttpRequest req) {
        return execute(null, req);
    }

    private HttpResponse execute(@Nullable EventLoop eventLoop, HttpRequest req) {
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
            final RequestHeaders newHeaders = req.headers().toBuilder().path(uri.getRawPath()).build();
            final HttpRequest newReq = HttpRequest.of(req, newHeaders);
            return execute(eventLoop, endpoint, newReq);
        }

        if (isUndefinedUri(uri())) {
            final IllegalArgumentException cause = new IllegalArgumentException("no authority: " + req.path());
            req.abort(cause);
            return HttpResponse.ofFailure(cause);
        }

        final String originalPath = req.path();
        final String newPath = concatPaths(uri().getRawPath(), originalPath);
        final HttpRequest newReq;
        // newPath and originalPath should be the same reference if uri().getRawPath() can be ignorable
        if (newPath != originalPath) {
            newReq = HttpRequest.of(req, req.headers().toBuilder().path(newPath).build());
        } else {
            newReq = req;
        }
        return execute(eventLoop, endpoint(), newReq);
    }

    private HttpResponse execute(@Nullable EventLoop eventLoop, Endpoint endpoint, HttpRequest req) {
        final PathAndQuery pathAndQuery = PathAndQuery.parse(req.path());
        if (pathAndQuery == null) {
            final IllegalArgumentException cause = new IllegalArgumentException("invalid path: " + req.path());
            req.abort(cause);
            return HttpResponse.ofFailure(cause);
        }
        return execute(eventLoop, endpoint, req.method(),
                       pathAndQuery.path(), pathAndQuery.query(), null, req,
                       (ctx, cause) -> {
                           if (ctx != null && !ctx.log().isAvailable(RequestLogAvailability.REQUEST_START)) {
                               // An exception is raised even before sending a request, so abort the request to
                               // release the elements.
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
        return execute(null, aggregatedReq);
    }

    HttpResponse execute(@Nullable EventLoop eventLoop, AggregatedHttpRequest aggregatedReq) {
        return execute(eventLoop, HttpRequest.of(aggregatedReq));
    }
}
