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
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.splitPathAndQuery;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.DefaultHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;

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

        final String[] pathAndQuery = splitPathAndQuery(concatPaths);
        if (pathAndQuery == null) {
            req.abort();
            return HttpResponse.ofFailure(new IllegalArgumentException("invalid path: " + concatPaths));
        }

        return execute(eventLoop, req.method(), pathAndQuery[0], pathAndQuery[1], null, req, cause -> {
            final DefaultHttpResponse res = new DefaultHttpResponse();
            res.close(cause);
            return res;
        });
    }

    @Override
    public HttpResponse execute(AggregatedHttpMessage aggregatedReq) {
        return execute(null, aggregatedReq);
    }

    HttpResponse execute(@Nullable EventLoop eventLoop, AggregatedHttpMessage aggregatedReq) {
        return execute(eventLoop, aggregatedReq.toHttpRequest());
    }
}
