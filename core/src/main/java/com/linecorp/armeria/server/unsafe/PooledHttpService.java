/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.unsafe;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.unsafe.PooledHttpData;
import com.linecorp.armeria.common.unsafe.PooledHttpRequest;
import com.linecorp.armeria.common.unsafe.PooledHttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * An HTTP/2 {@link Service} which publishes {@link PooledHttpData}.
 */
@FunctionalInterface
public interface PooledHttpService extends HttpService {

    /**
     * Creates a {@link PooledHttpService} that delegates to the provided {@link HttpService} for issuing
     * requests.
     */
    static PooledHttpService of(HttpService delegate) {
        if (delegate instanceof PooledHttpService) {
            return (PooledHttpService) delegate;
        }
        return new DefaultPooledHttpService(delegate);
    }

    /**
     * Called by framework to serve the request.
     *
     * @deprecated Do not extend this method, extend {@link #serve(ServiceRequestContext, PooledHttpRequest)}
     *     instead because this is a {@link PooledHttpService}.
     */
    @Override
    @Deprecated
    default HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return serve(ctx, PooledHttpRequest.of(req));
    }

    /**
     * Serves an incoming {@link PooledHttpRequest}.
     *
     * @param ctx the context of the received {@link PooledHttpRequest}
     * @param req the received {@link PooledHttpRequest}
     *
     * @return the {@link PooledHttpResponse}
     */
    PooledHttpResponse serve(ServiceRequestContext ctx, PooledHttpRequest req) throws Exception;
}
