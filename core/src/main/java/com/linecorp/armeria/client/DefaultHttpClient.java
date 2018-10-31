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

import static com.linecorp.armeria.internal.ArmeriaHttpUtil.concatPaths;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.internal.PathAndQuery;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;

final class DefaultHttpClient extends UserClient<HttpRequest, HttpResponse> implements HttpClient {

    DefaultHttpClient(ClientBuilderParams params, Client<HttpRequest, HttpResponse> delegate,
                      MeterRegistry meterRegistry, SessionProtocol sessionProtocol, Endpoint endpoint) {
        super(params, delegate, meterRegistry, sessionProtocol, endpoint);
    }

    @Override
    public HttpResponse execute(HttpRequest req) {
        return execute(null, req);
    }

    private HttpResponse execute(@Nullable EventLoop eventLoop, HttpRequest req) {
        final String concatPaths = concatPaths(uri().getRawPath(), req.path());
        req.path(concatPaths);

        final PathAndQuery pathAndQuery = PathAndQuery.parse(concatPaths);
        if (pathAndQuery == null) {
            req.abort();
            return HttpResponse.ofFailure(new IllegalArgumentException("invalid path: " + concatPaths));
        }

        return execute(eventLoop, req.method(), pathAndQuery.path(), pathAndQuery.query(), null, req,
                       (ctx, cause) -> {
                           if (ctx != null && !ctx.log().isAvailable(RequestLogAvailability.REQUEST_START)) {
                               // An exception is raised even before sending a request, so abort the request to
                               // release the elements.
                               req.abort();
                           }
                           return HttpResponse.ofFailure(cause);
                       });
    }

    @Override
    public HttpResponse execute(AggregatedHttpMessage aggregatedReq) {
        return execute(null, aggregatedReq);
    }

    HttpResponse execute(@Nullable EventLoop eventLoop, AggregatedHttpMessage aggregatedReq) {
        return execute(eventLoop, HttpRequest.of(aggregatedReq));
    }
}
